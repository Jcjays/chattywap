package com.adonis.chattywap.message;

import com.adonis.chattywap.message.dto.UnreadCountResponse;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A conversation's history is ordered newest first ({@code createdAt desc}); the ID breaks ties, so the order is
 * the same on every request. The queries read the index on {@code (conversation_id, created_at, id)} backwards, so
 * they don't sort, and stop once they have a page.
 */
interface MessageRepository extends JpaRepository<Message, UUID> {

	/**
	 * One page of the conversation's history by offset ({@code OFFSET/FETCH FIRST} in the database), without a count:
	 * the caller takes the total from {@link #findLastSequenceNumber}. The cost grows with the offset, because the
	 * skipped messages are read too; cursors ({@link #findByConversationAfter}) don't have that cost.
	 */
	@Query("""
			select m from Message m
			where m.conversationId = :conversationId
			order by m.createdAt desc, m.id desc
			""")
	List<Message> findPageByConversation(UUID conversationId, Pageable pageable);

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

	/**
	 * The conversation's highest sequence number, 0 if it has no messages. PostgreSQL reads the last entry of the
	 * index on {@code (conversation_id, sequence_number)}.
	 * <p>
	 * It is also the number of the conversation's messages, whatever their number: sequence numbers start at 1 and
	 * have no gaps, messages are never deleted, and a number is assigned only once the message before it has
	 * committed, so the committed messages are always exactly 1 to this number ({@code MessageService}).
	 */
	@Query("select coalesce(max(m.sequenceNumber), 0) from Message m where m.conversationId = :conversationId")
	long findLastSequenceNumber(UUID conversationId);

	/** The message's sequence number, if it is a message of this conversation. */
	@Query("select m.sequenceNumber from Message m where m.id = :id and m.conversationId = :conversationId")
	Optional<Long> findSequenceNumber(UUID conversationId, UUID id);

	/**
	 * How many messages in each of the conversations someone other than {@code userId} sent: the unread count of a
	 * member without a read position. Conversations without such messages are left out. Answered from the index on
	 * {@code (conversation_id, sequence_number)}, which includes the sender.
	 */
	@Query("""
			select new com.adonis.chattywap.message.dto.UnreadCountResponse(m.conversationId, count(*))
			from Message m
			where m.conversationId in :conversationIds and m.senderId <> :userId
			group by m.conversationId
			""")
	List<UnreadCountResponse> countFromOthers(UUID userId, Collection<UUID> conversationIds);

}
