package com.adonis.chattywap.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	Optional<Conversation> findByDirectUser1IdAndDirectUser2Id(UUID directUser1Id, UUID directUser2Id);

	/**
	 * Finds the conversation and locks its row until the transaction ends. In PostgreSQL, Hibernate uses
	 * {@code SELECT ... FOR NO KEY UPDATE}: it blocks other membership changes, but not the foreign-key checks
	 * of inserted members, which only need {@code FOR KEY SHARE}.
	 * Every membership change takes this lock first, so changes to the same conversation run one after the
	 * other and each one sees the members the previous one left.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Conversation c where c.id = :id")
	Optional<Conversation> findByIdForUpdate(UUID id);

	/**
	 * One page of the conversations {@code userId} is a member of, most recently updated first, with the
	 * user's role and the member count. The ID breaks ties, so the order is the same on every request.
	 * <p>
	 * Pagination happens in the database ({@code OFFSET/FETCH FIRST}); the count query only reads the user's
	 * memberships. Both counts use {@code count(*)}, not {@code count(m)}: Hibernate turns the latter into
	 * {@code count(id)}, and {@code id} isn't in the indexes, so PostgreSQL would have to read the table rows.
	 */
	@Query(value = """
			select new com.adonis.chattywap.conversation.ConversationListRow(c, m.role,
				(select count(*) from ConversationMember other where other.conversationId = c.id))
			from Conversation c
			join ConversationMember m on m.conversationId = c.id
			where m.userId = :userId
			order by c.updatedAt desc, c.id desc
			""",
			countQuery = "select count(*) from ConversationMember m where m.userId = :userId")
	Page<ConversationListRow> findPageByMember(UUID userId, Pageable pageable);

}
