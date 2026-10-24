package com.adonis.chattywap.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Version {@value #EVENT_VERSION} of the {@value #EVENT_TYPE} event: a message was stored. Published to RabbitMQ
 * through the transactional outbox, with routing key {@value #ROUTING_KEY}, once for every stored message, whether
 * it was sent through REST or STOMP. The JSON is this record's fields, serialized like the REST API's JSON: IDs as
 * strings, times in ISO 8601.
 * <p>
 * It identifies the message, never its content: consumers that need more read it through the owning feature's
 * service. It holds nothing about the sender beyond their ID, and no tokens.
 * <p>
 * Delivery is at least once, so consumers must tolerate receiving the same event again (same {@code eventId}).
 * Compatible changes (new optional fields) keep the version, and consumers ignore fields they don't know. Any other
 * change (removing, renaming or changing the meaning of a field) is a new version.
 *
 * @param eventId        unique per event; the same in every delivery of this event. Also the AMQP message ID.
 * @param eventType      always {@value #EVENT_TYPE}
 * @param eventVersion   always {@value #EVENT_VERSION} for this record
 * @param occurredAt     when the message was stored: its {@code createdAt}, in microseconds
 * @param messageId      the stored message
 * @param conversationId the conversation it was sent to
 * @param senderId       the user who sent it
 */
public record MessageCreatedEvent(
		UUID eventId,
		String eventType,
		int eventVersion,
		Instant occurredAt,
		UUID messageId,
		UUID conversationId,
		UUID senderId) {

	public static final String EVENT_TYPE = "MessageCreated";

	public static final int EVENT_VERSION = 1;

	/** The routing key on the {@code chattywap.events} exchange. */
	public static final String ROUTING_KEY = "message.created";

	/** A new event, with a new event ID, for a message that was stored at {@code occurredAt}. */
	public static MessageCreatedEvent of(UUID messageId, UUID conversationId, UUID senderId, Instant occurredAt) {
		return new MessageCreatedEvent(UUID.randomUUID(), EVENT_TYPE, EVENT_VERSION, occurredAt, messageId,
				conversationId, senderId);
	}

}
