package com.adonis.chattywap.conversation;

import com.adonis.chattywap.conversation.dto.ConversationMemberResponse;
import com.adonis.chattywap.conversation.dto.ConversationResponse;
import com.adonis.chattywap.conversation.dto.ConversationSummaryResponse;
import com.adonis.chattywap.conversation.dto.DirectConversationResult;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserSummary;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point of the {@code conversation} feature. Every method takes the caller's user ID, which the
 * controller reads from the access token, and checks membership and roles itself.
 * <p>
 * Rules:
 * <ul>
 * <li>Only members can see a conversation. To everyone else it doesn't exist: they get the same 404 as for an
 * unknown ID, so guessing IDs reveals nothing.</li>
 * <li>Direct conversations have exactly two members and there is at most one per pair of users. Nobody can be
 * added to, removed from or leave a direct conversation.</li>
 * <li>The creator of a group is its administrator. Only administrators add and remove other members, and
 * administrators can remove other administrators. New members join as {@link MemberRole#MEMBER}.</li>
 * <li>Any member can leave a group. A group with members always has an administrator: when the last one
 * leaves, the longest-standing remaining member becomes administrator. When the last member leaves, the
 * group stays in the database without members, where nobody can reach it.</li>
 * </ul>
 * Membership changes lock the conversation row first, so concurrent changes to one conversation run one after
 * the other. Without the lock, two concurrent requests could, for example, add the same user twice, or the
 * last administrator could leave while the member chosen as the new administrator leaves too.
 */
@Service
public class ConversationService {

	private static final String DIRECT_PAIR_CONSTRAINT = "conversations_direct_pair_key";

	/**
	 * Oldest members first. Members who joined at the same moment are ordered by user ID, so the order is stable.
	 * The IDs are compared as text, the order clients and PostgreSQL see; {@link UUID#compareTo} differs from it.
	 */
	private static final Comparator<ConversationMember> MEMBER_ORDER = Comparator
			.comparing(ConversationMember::getJoinedAt)
			.thenComparing(member -> member.getUserId().toString());

	private final ConversationRepository conversationRepository;

	private final ConversationMemberRepository memberRepository;

	private final UserService userService;

	/** Always starts a new transaction, even if the caller already has one: see {@link #createDirectConversation}. */
	private final TransactionTemplate newTransaction;

	public ConversationService(ConversationRepository conversationRepository,
			ConversationMemberRepository memberRepository, UserService userService,
			PlatformTransactionManager transactionManager) {
		this.conversationRepository = conversationRepository;
		this.memberRepository = memberRepository;
		this.userService = userService;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Returns the direct conversation between the caller and the recipient, creating it if it doesn't exist yet.
	 * <p>
	 * Not {@code @Transactional}, because it may need two transactions. Two requests for the same pair can both
	 * find no conversation and both try to insert one. The unique constraint on the pair lets only one insert
	 * succeed. PostgreSQL makes the other insert wait until the first transaction commits and then fails it;
	 * that request then reads the conversation the first one created, in a new transaction.
	 * <p>
	 * Both attempts run in transactions of their own, even when called from inside another transaction: a
	 * failed insert aborts its transaction in PostgreSQL, so the retry can't share it. A conversation created
	 * here therefore stays created if the caller's transaction later rolls back.
	 *
	 * @throws DirectConversationWithSelfException if the recipient is the caller
	 * @throws UnknownUsersException if the recipient doesn't exist
	 */
	public DirectConversationResult createDirectConversation(UUID callerId, UUID recipientId) {
		if (callerId.equals(recipientId)) {
			throw new DirectConversationWithSelfException();
		}
		DirectPair pair = DirectPair.of(callerId, recipientId);
		try {
			return newTransaction.execute(status -> findOrCreateDirectConversation(pair, callerId, recipientId));
		}
		catch (DataIntegrityViolationException ex) {
			if (!isViolationOf(ex, DIRECT_PAIR_CONSTRAINT)) {
				throw ex;
			}
			return newTransaction.execute(status -> findOrCreateDirectConversation(pair, callerId, recipientId));
		}
	}

	private DirectConversationResult findOrCreateDirectConversation(DirectPair pair, UUID callerId, UUID recipientId) {
		Optional<Conversation> existing =
				conversationRepository.findByDirectUser1IdAndDirectUser2Id(pair.user1Id(), pair.user2Id());
		if (existing.isPresent()) {
			Conversation conversation = existing.get();
			return new DirectConversationResult(
					toResponse(conversation, memberRepository.findByConversationId(conversation.getId())), false);
		}

		requireUsersExist(Set.of(recipientId));
		Instant now = now();
		Conversation conversation = conversationRepository.save(Conversation.direct(pair, callerId, now));
		List<ConversationMember> members = memberRepository.saveAll(List.of(
				new ConversationMember(conversation.getId(), callerId, MemberRole.MEMBER, now),
				new ConversationMember(conversation.getId(), recipientId, MemberRole.MEMBER, now)));
		// Flush now so that a duplicate pair surfaces here, as a DataIntegrityViolationException, not at commit.
		conversationRepository.flush();
		return new DirectConversationResult(toResponse(conversation, members), true);
	}

	/**
	 * Creates a group with the caller as administrator and the given users as members. Duplicate IDs and the
	 * caller's own ID in {@code memberIds} are ignored. The conversation and all memberships are written in one
	 * transaction: either all of them are stored or none.
	 *
	 * @throws UnknownUsersException if any of the users doesn't exist
	 */
	@Transactional
	public ConversationResponse createGroupConversation(UUID callerId, String name, Collection<UUID> memberIds) {
		Set<UUID> otherMemberIds = new LinkedHashSet<>(memberIds);
		otherMemberIds.remove(callerId);
		requireUsersExist(otherMemberIds);

		Instant now = now();
		// strip(), not trim(): @NotBlank and strip() agree on what whitespace is (see UserService#createUser).
		Conversation conversation = conversationRepository.save(Conversation.group(name.strip(), callerId, now));
		List<ConversationMember> members = new ArrayList<>();
		members.add(new ConversationMember(conversation.getId(), callerId, MemberRole.ADMIN, now));
		for (UUID memberId : otherMemberIds) {
			members.add(new ConversationMember(conversation.getId(), memberId, MemberRole.MEMBER, now));
		}
		memberRepository.saveAll(members);
		return toResponse(conversation, members);
	}

	/**
	 * One page of the caller's conversations, most recently updated first; conversations updated at the same
	 * moment are ordered by ID. The order is fixed, so a page always starts where the previous one ended as
	 * long as no conversation changes in between.
	 *
	 * @param page zero-based page number
	 */
	@Transactional(readOnly = true)
	public Page<ConversationSummaryResponse> listConversations(UUID callerId, int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size);
		// Spring Data JPA refuses offsets beyond Integer.MAX_VALUE. Such a page lies far past the end of any
		// list, so it gets the same answer as every page past the end: no entries.
		if (pageRequest.getOffset() > Integer.MAX_VALUE) {
			return new PageImpl<>(List.of(), pageRequest, memberRepository.countByUserId(callerId));
		}
		Page<ConversationListRow> rows = conversationRepository.findPageByMember(callerId, pageRequest);

		// The other participant of each direct conversation on the page, looked up in one query.
		Set<UUID> otherUserIds = rows.stream()
				.map(ConversationListRow::conversation)
				.filter(conversation -> !conversation.isGroup())
				.map(conversation -> conversation.otherDirectUser(callerId))
				.collect(Collectors.toSet());
		Map<UUID, UserSummary> users = userService.findUserSummaries(otherUserIds);

		return rows.map(row -> {
			Conversation conversation = row.conversation();
			UserSummary otherUser = conversation.isGroup()
					? null
					: requireUser(users, conversation.otherDirectUser(callerId));
			return new ConversationSummaryResponse(conversation.getId(), conversation.getType(),
					conversation.getName(), row.role(), row.memberCount(), otherUser, conversation.getCreatedAt(),
					conversation.getUpdatedAt());
		});
	}

	/**
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public ConversationResponse getConversation(UUID callerId, UUID conversationId) {
		Conversation conversation = conversationRepository.findById(conversationId)
				.orElseThrow(ConversationNotFoundException::new);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		requireMember(members, callerId);
		return toResponse(conversation, members);
	}

	/**
	 * Adds users to a group as {@link MemberRole#MEMBER}s. Users who are already members are skipped, so
	 * repeating a request changes nothing.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller isn't an administrator
	 * @throws UnknownUsersException if any of the users doesn't exist
	 */
	@Transactional
	public ConversationResponse addMembers(UUID callerId, UUID conversationId, Collection<UUID> userIds) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = new ArrayList<>(memberRepository.findByConversationId(conversationId));
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation);
		requireAdmin(caller);

		Set<UUID> newMemberIds = new LinkedHashSet<>(userIds);
		members.forEach(member -> newMemberIds.remove(member.getUserId()));
		requireUsersExist(newMemberIds);

		if (!newMemberIds.isEmpty()) {
			Instant now = now();
			List<ConversationMember> added = newMemberIds.stream()
					.map(userId -> new ConversationMember(conversationId, userId, MemberRole.MEMBER, now))
					.toList();
			memberRepository.saveAll(added);
			members.addAll(added);
			conversation.touch(now);
		}
		return toResponse(conversation, members);
	}

	/**
	 * Removes a member from a group. Removing yourself is the same as {@link #leave leaving} and needs no
	 * particular role; removing someone else needs the administrator role.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller removes someone else without being an administrator
	 * @throws MemberNotFoundException if the user to remove isn't a member
	 */
	@Transactional
	public void removeMember(UUID callerId, UUID conversationId, UUID userId) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation);

		if (callerId.equals(userId)) {
			removeLeavingMember(conversation, members, caller);
			return;
		}
		requireAdmin(caller);
		ConversationMember member = findMember(members, userId).orElseThrow(MemberNotFoundException::new);
		// The caller is an administrator and stays, so the group keeps at least one administrator.
		memberRepository.delete(member);
		conversation.touch(now());
	}

	/**
	 * The caller leaves a group. If they are its last administrator, the longest-standing remaining member
	 * becomes administrator.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 */
	@Transactional
	public void leave(UUID callerId, UUID conversationId) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation);
		removeLeavingMember(conversation, members, caller);
	}

	private void removeLeavingMember(Conversation conversation, List<ConversationMember> members,
			ConversationMember leaving) {
		memberRepository.delete(leaving);
		List<ConversationMember> remaining = members.stream()
				.filter(member -> member != leaving)
				.sorted(MEMBER_ORDER)
				.toList();
		if (!remaining.isEmpty() && remaining.stream().noneMatch(ConversationMember::isAdmin)) {
			remaining.getFirst().promoteToAdmin();
		}
		conversation.touch(now());
	}

	/**
	 * Locks the conversation for a membership change. Non-members are turned away before the lock is taken, so
	 * they can't hold up changes to conversations they don't belong to. Callers read the members after the lock
	 * and check the membership again, because it may have changed in between.
	 */
	private Conversation lockConversationAsMember(UUID conversationId, UUID callerId) {
		if (!memberRepository.existsByConversationIdAndUserId(conversationId, callerId)) {
			throw new ConversationNotFoundException();
		}
		return conversationRepository.findByIdForUpdate(conversationId)
				.orElseThrow(ConversationNotFoundException::new);
	}

	/**
	 * Non-members get the same exception as for a conversation that doesn't exist.
	 */
	private static ConversationMember requireMember(List<ConversationMember> members, UUID userId) {
		return findMember(members, userId).orElseThrow(ConversationNotFoundException::new);
	}

	private static Optional<ConversationMember> findMember(List<ConversationMember> members, UUID userId) {
		return members.stream().filter(member -> member.getUserId().equals(userId)).findFirst();
	}

	private static void requireGroup(Conversation conversation) {
		if (!conversation.isGroup()) {
			throw new GroupConversationRequiredException();
		}
	}

	private static void requireAdmin(ConversationMember member) {
		if (!member.isAdmin()) {
			throw new NotGroupAdminException();
		}
	}

	private void requireUsersExist(Set<UUID> userIds) {
		Set<UUID> existing = userService.findUserSummaries(userIds).keySet();
		List<UUID> unknown = userIds.stream().filter(userId -> !existing.contains(userId)).toList();
		if (!unknown.isEmpty()) {
			throw new UnknownUsersException(unknown);
		}
	}

	private ConversationResponse toResponse(Conversation conversation, List<ConversationMember> members) {
		Map<UUID, UserSummary> users =
				userService.findUserSummaries(members.stream().map(ConversationMember::getUserId).toList());
		List<ConversationMemberResponse> memberResponses = members.stream()
				.sorted(MEMBER_ORDER)
				.map(member -> new ConversationMemberResponse(member.getUserId(),
						requireUser(users, member.getUserId()).displayName(), member.getRole(), member.getJoinedAt()))
				.toList();
		return new ConversationResponse(conversation.getId(), conversation.getType(), conversation.getName(),
				conversation.getCreatedBy(), conversation.getCreatedAt(), conversation.getUpdatedAt(), memberResponses);
	}

	/**
	 * Members' user rows can't be deleted (foreign key with {@code on delete restrict}), so a miss is a bug.
	 */
	private static UserSummary requireUser(Map<UUID, UserSummary> users, UUID userId) {
		UserSummary user = users.get(userId);
		if (user == null) {
			throw new IllegalStateException("User " + userId + " of a conversation does not exist");
		}
		return user;
	}

	private static boolean isViolationOf(DataIntegrityViolationException ex, String constraintName) {
		return ex.getCause() instanceof ConstraintViolationException violation
				&& constraintName.equals(violation.getConstraintName());
	}

	/**
	 * PostgreSQL stores microseconds. Truncating keeps the times in a response identical to the ones read back
	 * from the database later.
	 */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
