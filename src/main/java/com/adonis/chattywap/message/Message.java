package com.adonis.chattywap.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of the {@code messages} table. Package-private: the entity never leaves the {@code message} feature.
 * <p>
 * Messages can't be edited or deleted, so no column is updatable.
 */
@Entity
@Table(name = "messages")
class Message {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	// The conversation and the sender are referenced by ID, not by a JPA association: their entities belong to the
	// conversation and user features.
	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	// The message's position in its conversation: 1 for the first message, one more for each following one.
	@Column(name = "sequence_number", nullable = false, updatable = false)
	private long sequenceNumber;

	@Column(name = "sender_id", nullable = false, updatable = false)
	private UUID senderId;

	@Column(name = "content", nullable = false, updatable = false)
	private String content;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Message() {
	}

	Message(UUID conversationId, long sequenceNumber, UUID senderId, String content, Instant createdAt) {
		this.conversationId = conversationId;
		this.sequenceNumber = sequenceNumber;
		this.senderId = senderId;
		this.content = content;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	UUID getConversationId() {
		return conversationId;
	}

	long getSequenceNumber() {
		return sequenceNumber;
	}

	UUID getSenderId() {
		return senderId;
	}

	String getContent() {
		return content;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

}
