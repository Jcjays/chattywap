package com.adonis.chattywap.conversation;

import com.adonis.chattywap.common.InvalidCursorException;
import com.adonis.chattywap.common.KeysetCursor;
import com.adonis.chattywap.common.PageMetadata;
import com.adonis.chattywap.conversation.dto.ConversationMemberResponse;
import com.adonis.chattywap.conversation.dto.ConversationPageResponse;
import com.adonis.chattywap.conversation.dto.ConversationResponse;
import com.adonis.chattywap.conversation.dto.ConversationSummaryResponse;
import com.adonis.chattywap.conversation.dto.DirectConversationResult;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserSummary;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
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
 * <li>The creator of a group is its administrator. Only administrators add and remove other members, promote
 * and demote administrators, rename the group and delete it. Administrators can remove and demote other
 * administrators. New members join as {@link MemberRole#MEMBER}.</li>
 * <li>A group has at most {@link #MAX_GROUP_MEMBERS} members, its creator included.</li>
 * <li>A group that isn't deleted always has an administrator. The last administrator can't demote themselves.
 * Any member can leave; when the last administrator leaves, the longest-standing remaining member becomes
 * administrator.</li>
 * <li>A group is deleted when an administrator deletes it or its last member leaves. Its memberships are removed,
 * the conversation row stays, marked deleted. Deleted groups are never shown or changed again.</li>
 * </ul>
 * Every change to a group locks the conversation row first, so concurrent changes to one conversation run one
 * after the other. Without the lock, two concurrent requests could, for example, add the same user twice, add
 * members past the limit, or leave the group without an administrator (the last administrator leaving while the
 * member chosen as the new one leaves too; two administrators demoting themselves at once). Activity recorded for
 * other features ({@link #recordActivity}, for new messages) takes the same lock, of any conversation.
 */
@Service
public class ConversationService {

	/**
	 * The most members a group can have, its creator included. Checked whenever members are added; groups that
	 * already had more before the limit existed keep their members, but nobody can join them.
	 */
	public static final int MAX_GROUP_MEMBERS = 100;

	private static final String DIRECT_PAIR_CONSTRAINT = "conversations_direct_pair_key";

	private static final String ROLES_NEED_GROUP = "Only group conversations have administrators.";

	private static final String ROLES_NEED_ADMIN = "Only group administrators can promote or demote members.";

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

	private final UnreadMessageCounter unreadMessageCounter;

	/** Always starts a new transaction, even if the caller already has one: see {@link #createDirectConversation}. */
	private final TransactionTemplate newTransaction;

	/**
	 * @param unreadMessageCounter the {@code message} feature's unread counts, for the conversation list
	 */
	public ConversationService(ConversationRepository conversationRepository,
			ConversationMemberRepository memberRepository, UserService userService,
			UnreadMessageCounter unreadMessageCounter, PlatformTransactionManager transactionManager) {
		this.conversationRepository = conversationRepository;
		this.memberRepository = memberRepository;
		this.userService = userService;
		this.unreadMessageCounter = unreadMessageCounter;
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
	 * <p>
	 * The member limit needs no lock here: until this transaction commits, nobody else can see the group, so
	 * nobody else can add members to it.
	 *
	 * @throws UnknownUsersException if any of the users doesn't exist
	 * @throws GroupMemberLimitExceededException if the creator and the other users are more than the limit
	 */
	@Transactional
	public ConversationResponse createGroupConversation(UUID callerId, String name, Collection<UUID> memberIds) {
		Set<UUID> otherMemberIds = new LinkedHashSet<>(memberIds);
		otherMemberIds.remove(callerId);
		requireUsersExist(otherMemberIds);
		requireWithinMemberLimit(1 + otherMemberIds.size());

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
	 * One page of the caller's conversations by page number, most recently updated first; conversations updated
	 * at the same moment are ordered by ID. Deleted groups are left out. The order is fixed, so a page always
	 * starts where the previous one ended as long as no conversation changes in between. The response's
	 * {@code nextCursor} continues with {@link #listConversationsAfter}, which doesn't have that restriction.
	 * <p>
	 * Each entry has the caller's unread count, from {@link UnreadMessageCounter}.
	 *
	 * @param page zero-based page number
	 */
	@Transactional(readOnly = true)
	public ConversationPageResponse listConversations(UUID callerId, int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size);
		// Spring Data JPA refuses offsets beyond Integer.MAX_VALUE. Such a page lies far past the end of any
		// list, so it gets the same answer as every page past the end: no entries.
		Page<ConversationListRow> rows = pageRequest.getOffset() > Integer.MAX_VALUE
				? new PageImpl<>(List.of(), pageRequest, conversationRepository.countByMember(callerId))
				: conversationRepository.findPageByMember(callerId, pageRequest);

		String nextCursor = rows.hasNext() && rows.hasContent()
				? cursorAfter(rows.getContent().getLast().conversation())
				: null;
		return new ConversationPageResponse(toSummaries(callerId, rows.getContent()),
				new PageMetadata(rows.getSize(), rows.getNumber(), rows.getTotalElements(), rows.getTotalPages()),
				nextCursor);
	}

	/**
	 * The caller's conversations that come after {@code cursor} in the list order (see
	 * {@link #listConversations}), at most {@code size} of them.
	 * <p>
	 * Unlike page numbers, a cursor is a position in the order, so conversations that are created, changed, left
	 * or deleted while the client pages through the list don't shift the entries after it: no entry appears
	 * twice and none is skipped because others moved. A conversation that changes moves to the start of the list,
	 * before the cursor, and only shows up when the client reloads the start. Each page reads the database as it
	 * is when it is requested; together, the pages aren't a snapshot of one moment.
	 *
	 * @param cursor a {@code nextCursor} from an earlier response
	 * @throws InvalidCursorException if the cursor isn't one the server issued
	 */
	@Transactional(readOnly = true)
	public ConversationPageResponse listConversationsAfter(UUID callerId, String cursor, int size) {
		KeysetCursor position = KeysetCursor.decode(cursor);
		// One more than requested tells whether another page follows, without a count query.
		List<ConversationListRow> rows = conversationRepository.findByMemberAfter(callerId, position.time(),
				position.id(), Limit.of(size + 1));

		boolean hasNext = rows.size() > size;
		List<ConversationListRow> page = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = hasNext ? cursorAfter(page.getLast().conversation()) : null;
		return new ConversationPageResponse(toSummaries(callerId, page), null, nextCursor);
	}

	/** The list position right after {@code conversation}: its sort key, {@code (updatedAt, id)}. */
	private static String cursorAfter(Conversation conversation) {
		return new KeysetCursor(conversation.getUpdatedAt(), conversation.getId()).encode();
	}

	private List<ConversationSummaryResponse> toSummaries(UUID callerId, List<ConversationListRow> rows) {
		// The other participant of each direct conversation on the page, looked up in one query.
		Set<UUID> otherUserIds = rows.stream()
				.map(ConversationListRow::conversation)
				.filter(conversation -> !conversation.isGroup())
				.map(conversation -> conversation.otherDirectUser(callerId))
				.collect(Collectors.toSet());
		Map<UUID, UserSummary> users = userService.findUserSummaries(otherUserIds);
		// The caller's unread counts of all conversations on the page, in at most two queries.
		Map<UUID, Long> unreadCounts = unreadMessageCounter.countUnread(callerId,
				rows.stream().map(row -> row.conversation().getId()).toList());

		return rows.stream().map(row -> {
			Conversation conversation = row.conversation();
			UserSummary otherUser = conversation.isGroup()
					? null
					: requireUser(users, conversation.otherDirectUser(callerId));
			return new ConversationSummaryResponse(conversation.getId(), conversation.getType(),
					conversation.getName(), row.role(), row.memberCount(), unreadCounts.get(conversation.getId()),
					otherUser, conversation.getCreatedAt(), conversation.getUpdatedAt());
		}).toList();
	}

	/**
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public ConversationResponse getConversation(UUID callerId, UUID conversationId) {
		Conversation conversation = conversationRepository.findById(conversationId)
				.filter(found -> !found.isDeleted())
				.orElseThrow(ConversationNotFoundException::new);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		requireMember(members, callerId);
		return toResponse(conversation, members);
	}

	/**
	 * Checks that the caller may see what the conversation contains, such as its messages: it exists, isn't
	 * deleted, and the caller is a member. For other features, which can't read this feature's tables.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public void requireMembership(UUID callerId, UUID conversationId) {
		if (!memberRepository.existsInActiveConversation(conversationId, callerId)) {
			throw new ConversationNotFoundException();
		}
	}

	/**
	 * Records activity of the caller in the conversation, such as posting a message, and returns its time. The
	 * conversation's {@code updatedAt} moves to that time, which moves the conversation to the top of its members'
	 * lists. For other features, which store the activity itself (the message) in the same transaction, with the
	 * returned time: if that fails, the transaction rolls back and {@code updatedAt} keeps its value.
	 * <p>
	 * Takes the conversation lock like every change to a group, after checking that the caller is a member, and
	 * checks the membership again under the lock. Until the calling transaction ends, the caller therefore stays a
	 * member: leaving, removal and deletion of the group wait, and if one of them came first, this call waits for it
	 * and then fails. Activities in one conversation also run one after the other. The time is read after the lock
	 * is taken, so as long as the clock doesn't go back, an activity that commits later gets a later time.
	 *
	 * @return the time of the activity, in microseconds
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Instant recordActivity(UUID callerId, UUID conversationId) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		if (!memberRepository.existsByConversationIdAndUserId(conversationId, callerId)) {
			throw new ConversationNotFoundException();
		}
		Instant now = now();
		conversation.touch(now);
		return now;
	}

	/**
	 * The user IDs of the conversation's members, the caller included. For other features that act for all members,
	 * such as delivering a new message to them. Called after {@link #recordActivity} in the same transaction, it reads
	 * the members under the conversation lock: the members at the time of the activity.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public Set<UUID> getMemberIds(UUID callerId, UUID conversationId) {
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		requireMember(members, callerId);
		return members.stream().map(ConversationMember::getUserId).collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Adds users to a group as {@link MemberRole#MEMBER}s. Users who are already members are skipped, so
	 * repeating a request changes nothing. Either all new users are added or, if they would take the group past
	 * the member limit, none.
	 * <p>
	 * The member count is read after the conversation lock is taken, and every addition takes that lock, so
	 * concurrent additions can't together exceed the limit: each one counts the members the previous one added.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller isn't an administrator
	 * @throws UnknownUsersException if any of the users doesn't exist
	 * @throws GroupMemberLimitExceededException if the new users would take the group past the limit
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
			requireWithinMemberLimit(members.size() + newMemberIds.size());
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
	 * becomes administrator. If they are its last member, the group is deleted.
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
		if (remaining.isEmpty()) {
			conversation.markDeleted(now());
			return;
		}
		if (remaining.stream().noneMatch(ConversationMember::isAdmin)) {
			remaining.getFirst().promoteToAdmin();
		}
		conversation.touch(now());
	}

	/**
	 * Makes a member of a group an administrator.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller isn't an administrator
	 * @throws MemberNotFoundException if the user to promote isn't a member
	 * @throws MemberAlreadyAdminException if they are an administrator already
	 */
	@Transactional
	public ConversationResponse promoteToAdmin(UUID callerId, UUID conversationId, UUID userId) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation, ROLES_NEED_GROUP);
		requireAdmin(caller, ROLES_NEED_ADMIN);
		ConversationMember member = findMember(members, userId).orElseThrow(MemberNotFoundException::new);

		if (member.isAdmin()) {
			throw new MemberAlreadyAdminException();
		}
		member.promoteToAdmin();
		conversation.touch(now());
		return toResponse(conversation, members);
	}

	/**
	 * Makes an administrator of a group a regular member again. Administrators can demote each other and
	 * themselves, but the group must keep at least one administrator.
	 * <p>
	 * Only an administrator demoting themselves can remove the last one: anyone else who demotes stays an
	 * administrator. The conversation lock makes two administrators demoting themselves at once run one after
	 * the other, so the second sees that it would be the last and is refused.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller isn't an administrator
	 * @throws MemberNotFoundException if the user to demote isn't a member
	 * @throws MemberNotAdminException if they aren't an administrator
	 * @throws LastGroupAdminException if they are the group's only administrator
	 */
	@Transactional
	public ConversationResponse demoteToMember(UUID callerId, UUID conversationId, UUID userId) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation, ROLES_NEED_GROUP);
		requireAdmin(caller, ROLES_NEED_ADMIN);
		ConversationMember member = findMember(members, userId).orElseThrow(MemberNotFoundException::new);

		if (!member.isAdmin()) {
			throw new MemberNotAdminException();
		}
		if (members.stream().filter(ConversationMember::isAdmin).count() == 1) {
			throw new LastGroupAdminException();
		}
		member.demoteToMember();
		conversation.touch(now());
		return toResponse(conversation, members);
	}

	/**
	 * Renames a group. Renaming it to its current name changes nothing.
	 *
	 * @param name valid as a group name (see {@code CreateGroupConversationRequest}); stored stripped of
	 *             surrounding whitespace
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller isn't an administrator
	 */
	@Transactional
	public ConversationResponse renameGroup(UUID callerId, UUID conversationId, String name) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation, "Only group conversations have a name.");
		requireAdmin(caller, "Only group administrators can rename the group.");

		String strippedName = name.strip();
		if (!strippedName.equals(conversation.getName())) {
			conversation.rename(strippedName, now());
		}
		return toResponse(conversation, members);
	}

	/**
	 * Deletes a group: removes all its memberships and marks the conversation deleted, in one transaction. To its
	 * former members the group is gone, the same as a conversation they never belonged to. The conversation row
	 * is kept for the records that refer to it.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws GroupConversationRequiredException if it's a direct conversation
	 * @throws NotGroupAdminException if the caller isn't an administrator
	 */
	@Transactional
	public void deleteGroup(UUID callerId, UUID conversationId) {
		Conversation conversation = lockConversationAsMember(conversationId, callerId);
		List<ConversationMember> members = memberRepository.findByConversationId(conversationId);
		ConversationMember caller = requireMember(members, callerId);
		requireGroup(conversation, "Only group conversations can be deleted.");
		requireAdmin(caller, "Only group administrators can delete the group.");

		memberRepository.deleteByConversationId(conversationId);
		conversation.markDeleted(now());
	}

	/**
	 * Locks the conversation for a change. Non-members are turned away before the lock is taken, so they can't
	 * hold up changes to conversations they don't belong to. Callers read the members after the lock and check
	 * the membership again, because it may have changed in between: the caller may have been removed, or the
	 * group deleted, while this request waited for the lock.
	 */
	private Conversation lockConversationAsMember(UUID conversationId, UUID callerId) {
		if (!memberRepository.existsByConversationIdAndUserId(conversationId, callerId)) {
			throw new ConversationNotFoundException();
		}
		return conversationRepository.findByIdForUpdate(conversationId)
				.filter(conversation -> !conversation.isDeleted())
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

	/** For adding, removing and leaving. */
	private static void requireGroup(Conversation conversation) {
		if (!conversation.isGroup()) {
			throw new GroupConversationRequiredException();
		}
	}

	private static void requireGroup(Conversation conversation, String detail) {
		if (!conversation.isGroup()) {
			throw new GroupConversationRequiredException(detail);
		}
	}

	/** For adding and removing others. */
	private static void requireAdmin(ConversationMember member) {
		if (!member.isAdmin()) {
			throw new NotGroupAdminException();
		}
	}

	private static void requireAdmin(ConversationMember member, String detail) {
		if (!member.isAdmin()) {
			throw new NotGroupAdminException(detail);
		}
	}

	/**
	 * @param memberCount how many members the group would have
	 */
	private static void requireWithinMemberLimit(int memberCount) {
		if (memberCount > MAX_GROUP_MEMBERS) {
			throw new GroupMemberLimitExceededException(MAX_GROUP_MEMBERS);
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
