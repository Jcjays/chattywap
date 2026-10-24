package com.adonis.chattywap.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of the {@code receipt_positions} table: how far one member of a conversation has acknowledged its
 * messages, as received and as read. Positions are message sequence numbers. Rows are written only by
 * {@link ReceiptPositionRepository}'s statements, which only ever move a position forward, so no column is updatable
 * through the entity.
 * <p>
 * The conversation and the member are referenced by ID: their entities belong to the conversation and user features.
 */
@Entity
@Table(name = "receipt_positions")
@IdClass(ReceiptPosition.Key.class)
class ReceiptPosition {

	@Id
	@Column(name = "conversation_id", updatable = false)
	private UUID conversationId;

	@Id
	@Column(name = "user_id", updatable = false)
	private UUID userId;

	@Column(name = "last_delivered_sequence_number", nullable = false, updatable = false)
	private long lastDeliveredSequenceNumber;

	@Column(name = "delivered_at", nullable = false, updatable = false)
	private Instant deliveredAt;

	// Null until the member reports reading a message.
	@Column(name = "last_read_sequence_number", updatable = false)
	private Long lastReadSequenceNumber;

	@Column(name = "read_at", updatable = false)
	private Instant readAt;

	protected ReceiptPosition() {
	}

	UUID getConversationId() {
		return conversationId;
	}

	UUID getUserId() {
		return userId;
	}

	long getLastDeliveredSequenceNumber() {
		return lastDeliveredSequenceNumber;
	}

	Instant getDeliveredAt() {
		return deliveredAt;
	}

	/** 0 if the member hasn't reported reading any message. */
	long getLastReadSequenceNumber() {
		return lastReadSequenceNumber == null ? 0 : lastReadSequenceNumber;
	}

	Instant getReadAt() {
		return readAt;
	}

	record Key(UUID conversationId, UUID userId) implements Serializable {
	}

}
