package com.adonis.chattywap.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of the {@code outbox_events} table: an event waiting to be published, or already published. Package-
 * private: the entity never leaves the {@code messaging} feature.
 * <p>
 * Rows are inserted with {@link #pending} and then changed only by {@link OutboxEventRepository}'s statements, which
 * the relay runs, so no column is updatable through the entity.
 * <p>
 * The ID is the event ID, which is also part of the payload, so the application assigns it instead of Hibernate.
 */
@Entity
@Table(name = "outbox_events")
class OutboxEvent {

	@Id
	private UUID id;

	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	@Column(name = "event_version", nullable = false, updatable = false)
	private int eventVersion;

	@Column(name = "routing_key", nullable = false, updatable = false)
	private String routingKey;

	@Column(name = "payload", nullable = false, updatable = false)
	private String payload;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "published_at", updatable = false)
	private Instant publishedAt;

	@Column(name = "attempts", nullable = false, updatable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false, updatable = false)
	private Instant nextAttemptAt;

	@Column(name = "last_error", updatable = false)
	private String lastError;

	@Column(name = "correlation_id", updatable = false)
	private String correlationId;

	protected OutboxEvent() {
	}

	private OutboxEvent(UUID id, String eventType, int eventVersion, String routingKey, String payload,
			Instant createdAt, String correlationId) {
		this.id = id;
		this.eventType = eventType;
		this.eventVersion = eventVersion;
		this.routingKey = routingKey;
		this.payload = payload;
		this.createdAt = createdAt;
		this.nextAttemptAt = createdAt;
		this.correlationId = correlationId;
	}

	/**
	 * An event that the relay may publish right away.
	 *
	 * @param correlationId the request ID of the request that wrote it, or {@code null}
	 */
	static OutboxEvent pending(UUID eventId, String eventType, int eventVersion, String routingKey, String payload,
			Instant createdAt, String correlationId) {
		return new OutboxEvent(eventId, eventType, eventVersion, routingKey, payload, createdAt, correlationId);
	}

	UUID getId() {
		return id;
	}

	String getEventType() {
		return eventType;
	}

	String getRoutingKey() {
		return routingKey;
	}

	String getPayload() {
		return payload;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	/** Attempts so far, the current one included once the relay has claimed the event. */
	int getAttempts() {
		return attempts;
	}

	/** The request ID of the request that wrote the event, or {@code null}. */
	String getCorrelationId() {
		return correlationId;
	}

}
