package com.adonis.chattywap.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of the {@code processed_events} table: an event that a consumer has processed. Rows are written only
 * by {@link ProcessedEventRepository#insertIfAbsent}, in the transaction of the consumer's effect.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Key.class)
class ProcessedEvent {

	@Id
	@Column(name = "consumer_name", updatable = false)
	private String consumerName;

	@Id
	@Column(name = "event_id", updatable = false)
	private UUID eventId;

	@Column(name = "processed_at", nullable = false, updatable = false)
	private Instant processedAt;

	protected ProcessedEvent() {
	}

	record Key(String consumerName, UUID eventId) implements Serializable {
	}

}
