package com.adonis.chattywap.conversation.dto;

import com.adonis.chattywap.conversation.ConversationType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A conversation with all its members. Only members get to see it.
 *
 * @param name      the group's name; {@code null} for direct conversations
 * @param createdBy ID of the user who created the conversation (who may have left since)
 * @param updatedAt the last change to the conversation or its members
 * @param members   ordered by the time they joined, oldest first; members who joined together by user ID
 */
public record ConversationResponse(
		UUID id,
		ConversationType type,
		String name,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt,
		List<ConversationMemberResponse> members) {
}
