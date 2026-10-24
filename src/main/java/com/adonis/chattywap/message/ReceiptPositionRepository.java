package com.adonis.chattywap.message;

import com.adonis.chattywap.message.dto.UnreadCountResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Positions only ever move forward. Each change is one statement that inserts the member's row or moves the position
 * in it, and does nothing if the row already is at that position or beyond it. PostgreSQL locks the row for the
 * statement, so concurrent acknowledgements of one member run one after the other and the furthest one wins, in
 * whatever order they arrive: a repeated or outdated acknowledgement can't move a position back.
 * <p>
 * Times don't move back either. The time an acknowledgement passes in was taken before its statement waited for the
 * row, possibly before the acknowledgement it waited for took its own. A position's time is therefore never earlier
 * than the time it replaces, and a read time never earlier than the delivered time already stored: reading comes after
 * receiving.
 * <p>
 * Inserting fails with a foreign-key violation ({@value ReceiptService#MEMBER_CONSTRAINT}) if the user isn't a member
 * of the conversation, for example because they were removed since their membership was checked.
 */
interface ReceiptPositionRepository extends JpaRepository<ReceiptPosition, ReceiptPosition.Key> {

	List<ReceiptPosition> findByConversationId(UUID conversationId);

	/**
	 * Moves the member's delivered position to {@code sequenceNumber}, if it is further than the current one.
	 *
	 * @return 1 if the position moved (or the row was inserted), 0 if it already was at least that far
	 */
	@Modifying
	@Query(value = """
			insert into receipt_positions (conversation_id, user_id, last_delivered_sequence_number, delivered_at)
			values (:conversationId, :userId, :sequenceNumber, :now)
			on conflict (conversation_id, user_id) do update
			set last_delivered_sequence_number = excluded.last_delivered_sequence_number,
			    delivered_at = greatest(excluded.delivered_at, receipt_positions.delivered_at)
			where receipt_positions.last_delivered_sequence_number < excluded.last_delivered_sequence_number
			""", nativeQuery = true)
	int advanceDelivered(UUID conversationId, UUID userId, long sequenceNumber, Instant now);

	/**
	 * Moves the member's read position to {@code sequenceNumber}, if it is further than the current one, and the
	 * delivered position along with it where it is behind: what was read was received.
	 *
	 * @return 1 if the read position moved (or the row was inserted), 0 if it already was at least that far
	 */
	@Modifying
	@Query(value = """
			insert into receipt_positions (conversation_id, user_id, last_delivered_sequence_number, delivered_at,
			                               last_read_sequence_number, read_at)
			values (:conversationId, :userId, :sequenceNumber, :now, :sequenceNumber, :now)
			on conflict (conversation_id, user_id) do update
			set last_read_sequence_number = excluded.last_read_sequence_number,
			    read_at = greatest(excluded.read_at, receipt_positions.read_at, receipt_positions.delivered_at),
			    last_delivered_sequence_number = greatest(receipt_positions.last_delivered_sequence_number,
			                                              excluded.last_delivered_sequence_number),
			    delivered_at = case
			        when receipt_positions.last_delivered_sequence_number < excluded.last_delivered_sequence_number
			        then greatest(excluded.delivered_at, receipt_positions.delivered_at)
			        else receipt_positions.delivered_at
			    end
			where coalesce(receipt_positions.last_read_sequence_number, 0) < excluded.last_read_sequence_number
			""", nativeQuery = true)
	int advanceRead(UUID conversationId, UUID userId, long sequenceNumber, Instant now);

	/**
	 * The unread count of each of the conversations in which {@code userId} has a read position: the messages others
	 * sent after it. Conversations without a read position are left out.
	 * <p>
	 * One count per position, each reading the index on {@code (conversation_id, sequence_number)} from the position
	 * on, and only the index, which includes the sender: the cost grows with the unread messages, not the history.
	 */
	@Query("""
			select new com.adonis.chattywap.message.dto.UnreadCountResponse(r.conversationId,
				(select count(*) from Message m
				 where m.conversationId = r.conversationId and m.sequenceNumber > r.lastReadSequenceNumber
				 	and m.senderId <> :userId))
			from ReceiptPosition r
			where r.userId = :userId and r.conversationId in :conversationIds and r.lastReadSequenceNumber is not null
			""")
	List<UnreadCountResponse> countUnreadAfterReadPositions(UUID userId, Collection<UUID> conversationIds);

}
