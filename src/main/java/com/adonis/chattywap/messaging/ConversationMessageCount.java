package com.adonis.chattywap.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of the {@code conversation_message_counts} table: how many {@code MessageCreated} events the example
 * consumer has processed for a conversation. Written only by {@link ConversationMessageCountRepository#increment}.
 * The conversation is referenced by ID: its entity belongs to the conversation feature.
 */
@Entity
@Table(name = "conversation_message_counts")
class ConversationMessageCount {

	@Id
	@Column(name = "conversation_id", updatable = false)
	private UUID conversationId;

	@Column(name = "message_count", nullable = false, updatable = false)
	private long messageCount;

	@Column(name = "updated_at", nullable = false, updatable = false)
	private Instant updatedAt;

	protected ConversationMessageCount() {
	}

}
