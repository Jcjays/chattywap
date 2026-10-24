package com.adonis.chattywap.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The relay's statements. Each is one short transaction of its own: the relay never holds a row lock, or any
 * transaction, while it talks to the broker. Instead of a lock, a claim reserves events with a lease: it moves
 * their {@code next_attempt_at} past the lease's end, so no other claim selects them before the lease has expired.
 * <p>
 * Times are PostgreSQL's ({@code now()}), so that relays on several application instances agree on when a lease
 * ends.
 */
interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	/**
	 * Claims up to {@code limit} events that are due: not published, and past their {@code next_attempt_at}. Counts
	 * the attempt and reserves them for {@code leaseMillis}. Returns them oldest first.
	 * <p>
	 * {@code skip locked}: rows that a concurrent claim has locked but not yet committed are left to it, instead of
	 * waiting for it and then finding them reserved. Concurrent claims therefore get different events and never wait
	 * for each other.
	 */
	@Transactional
	@Query(value = """
			with claimed as (
				update outbox_events
				set attempts = attempts + 1, next_attempt_at = now() + :leaseMillis * interval '1 millisecond'
				where id in (
					select id from outbox_events
					where published_at is null and next_attempt_at <= now()
					order by next_attempt_at
					limit :limit
					for update skip locked)
				returning *)
			select * from claimed order by created_at, id
			""", nativeQuery = true)
	List<OutboxEvent> claimDue(int limit, long leaseMillis);

	/**
	 * Records that the broker confirmed these events. Events already marked (another relay published them too, after
	 * a lease expired) keep their first time.
	 */
	@Transactional
	@Modifying
	@Query(value = "update outbox_events set published_at = now() where id in (:ids) and published_at is null",
			nativeQuery = true)
	int markPublished(Collection<UUID> ids);

	/**
	 * Records a failed attempt: the event stays pending and is due again after {@code retryDelayMillis}. Does
	 * nothing to an event that was published meanwhile, or claimed again: if this attempt's lease expired and another
	 * relay claimed the event, the attempt count has moved on, and that relay's lease stays as it is.
	 *
	 * @param attempt the event's attempt count when it was claimed for the attempt that failed
	 * @param error   why it failed, at most 1000 characters
	 */
	@Transactional
	@Modifying
	@Query(value = """
			update outbox_events
			set last_error = :error, next_attempt_at = now() + :retryDelayMillis * interval '1 millisecond'
			where id = :id and attempts = :attempt and published_at is null
			""", nativeQuery = true)
	int markFailed(UUID id, int attempt, String error, long retryDelayMillis);

	@Query("select count(*) from OutboxEvent e where e.publishedAt is null")
	long countPending();

	/** When the oldest event that isn't published yet was written, or {@code null} if all are published. */
	@Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
	Instant findOldestPendingCreatedAt();

}
