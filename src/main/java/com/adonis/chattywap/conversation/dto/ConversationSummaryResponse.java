package com.adonis.chattywap.conversation.dto;

import com.adonis.chattywap.conversation.ConversationType;
import com.adonis.chattywap.conversation.MemberRole;
import com.adonis.chattywap.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the caller's conversation list. Enough to render the list; the full member list is in
 * {@link ConversationResponse}.
 *
 * @param name        the group's name; {@code null} for direct conversations
 * @param role        the caller's role in the conversation
 * @param memberCount how many members the conversation has, the caller included
 * @param otherUser   direct conversations: the other participant; {@code null} for groups
 */
public record ConversationSummaryResponse(
		UUID id,
		ConversationType type,
		String name,
		MemberRole role,
		long memberCount,
		UserSummary otherUser,
		Instant createdAt,
		Instant updatedAt) {
}
