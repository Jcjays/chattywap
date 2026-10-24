package com.adonis.chattywap.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

	/**
	 * Records that the consumer processed the event, unless it already had. Returns 1 if this call recorded it, 0 if
	 * it was recorded before.
	 * <p>
	 * Two deliveries of the same event processed at the same time can't both insert: the second waits for the first
	 * transaction's row, and finds it once that commits (0), or inserts it if that rolls back (1).
	 */
	@Modifying
	@Query(value = """
			insert into processed_events (consumer_name, event_id, processed_at)
			values (:consumerName, :eventId, :processedAt)
			on conflict do nothing
			""", nativeQuery = true)
	int insertIfAbsent(String consumerName, UUID eventId, Instant processedAt);

}
