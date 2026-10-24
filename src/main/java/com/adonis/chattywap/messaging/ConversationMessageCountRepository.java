package com.adonis.chattywap.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

interface ConversationMessageCountRepository extends JpaRepository<ConversationMessageCount, UUID> {

	/**
	 * Adds one to the conversation's count, starting at 1. One statement, so concurrent increments of the same
	 * conversation can't lose one. Fails with a foreign-key violation if the conversation doesn't exist.
	 */
	@Modifying
	@Query(value = """
			insert into conversation_message_counts (conversation_id, message_count, updated_at)
			values (:conversationId, 1, :now)
			on conflict (conversation_id) do update
			set message_count = conversation_message_counts.message_count + 1, updated_at = excluded.updated_at
			""", nativeQuery = true)
	void increment(UUID conversationId, Instant now);

}
