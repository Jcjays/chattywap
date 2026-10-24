package com.adonis.chattywap.message;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A conversation's history is ordered newest first ({@code createdAt desc}); the ID breaks ties, so the order is
 * the same on every request. The queries read the index on {@code (conversation_id, created_at, id)} backwards, so
 * they don't sort, and stop once they have a page.
 */
interface MessageRepository extends JpaRepository<Message, UUID> {

	String COUNT_BY_CONVERSATION = "select count(*) from Message m where m.conversationId = :conversationId";

	/**
	 * One page of the conversation's history by offset ({@code OFFSET/FETCH FIRST} in the database).
	 */
	@Query(value = """
			select m from Message m
			where m.conversationId = :conversationId
			order by m.createdAt desc, m.id desc
			""",
			countQuery = COUNT_BY_CONVERSATION)
	Page<Message> findPageByConversation(UUID conversationId, Pageable pageable);

	@Query(COUNT_BY_CONVERSATION)
	long countByConversation(UUID conversationId);

	/**
	 * Like {@link #findPageByConversation}, but starts after a position instead of at an offset: returns the
	 * messages that sort after ({@code createdAt}, {@code id}) in the history's order, which are the older ones, at
	 * most {@code limit} of them.
	 * <p>
	 * The condition compares the two values the history is ordered by as one row value, so PostgreSQL uses it as
	 * the start of the index scan instead of filtering the newer messages one by one.
	 */
	@Query("""
			select m from Message m
			where m.conversationId = :conversationId and (m.createdAt, m.id) < (:createdAt, :id)
			order by m.createdAt desc, m.id desc
			""")
	List<Message> findByConversationAfter(UUID conversationId, Instant createdAt, UUID id, Limit limit);

}
