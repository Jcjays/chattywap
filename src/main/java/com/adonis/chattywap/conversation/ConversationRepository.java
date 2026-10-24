package com.adonis.chattywap.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	/**
	 * How many conversations the user's list has. Deleted groups have no members, so the join to
	 * {@code conversations} only matters if that ever fails to hold; it keeps the count and the pages in agreement.
	 * {@code count(*)}, not {@code count(m)}: see {@link #findPageByMember}.
	 */
	String COUNT_BY_MEMBER = """
			select count(*) from ConversationMember m
			join Conversation c on c.id = m.conversationId
			where m.userId = :userId and c.deletedAt is null
			""";

	Optional<Conversation> findByDirectUser1IdAndDirectUser2Id(UUID directUser1Id, UUID directUser2Id);

	/**
	 * Finds the conversation and locks its row until the transaction ends. In PostgreSQL, Hibernate uses
	 * {@code SELECT ... FOR NO KEY UPDATE}: it blocks other membership changes, but not the foreign-key checks
	 * of inserted members, which only need {@code FOR KEY SHARE}.
	 * Every change to a group (members, roles, name, deletion) takes this lock first, so changes to the same
	 * conversation run one after the other and each one sees the members the previous one left. New messages take
	 * it too, in any conversation ({@code ConversationService#recordActivity}).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Conversation c where c.id = :id")
	Optional<Conversation> findByIdForUpdate(UUID id);

	/**
	 * One page of the conversations {@code userId} is a member of, most recently updated first, with the
	 * user's role and the member count. The ID breaks ties, so the order is the same on every request.
	 * Deleted groups are left out.
	 * <p>
	 * Pagination happens in the database ({@code OFFSET/FETCH FIRST}). Both counts use {@code count(*)}, not
	 * {@code count(m)}: Hibernate turns the latter into {@code count(id)}, and {@code id} isn't in the indexes, so
	 * PostgreSQL would have to read the membership rows.
	 */
	@Query(value = """
			select new com.adonis.chattywap.conversation.ConversationListRow(c, m.role,
				(select count(*) from ConversationMember other where other.conversationId = c.id))
			from Conversation c
			join ConversationMember m on m.conversationId = c.id
			where m.userId = :userId and c.deletedAt is null
			order by c.updatedAt desc, c.id desc
			""",
			countQuery = COUNT_BY_MEMBER)
	Page<ConversationListRow> findPageByMember(UUID userId, Pageable pageable);

	@Query(COUNT_BY_MEMBER)
	long countByMember(UUID userId);

	/**
	 * Like {@link #findPageByMember}, but starts after a position instead of at an offset: returns the entries
	 * that sort after ({@code updatedAt}, {@code id}) in the list's order, at most {@code limit} of them.
	 * <p>
	 * The condition compares the same two values the list is ordered by, so entries before the position never
	 * reappear and entries added before it or removed from anywhere don't shift the following ones.
	 */
	@Query("""
			select new com.adonis.chattywap.conversation.ConversationListRow(c, m.role,
				(select count(*) from ConversationMember other where other.conversationId = c.id))
			from Conversation c
			join ConversationMember m on m.conversationId = c.id
			where m.userId = :userId and c.deletedAt is null
				and (c.updatedAt < :updatedAt or (c.updatedAt = :updatedAt and c.id < :id))
			order by c.updatedAt desc, c.id desc
			""")
	List<ConversationListRow> findByMemberAfter(UUID userId, Instant updatedAt, UUID id, Limit limit);

}
