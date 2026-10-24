package com.adonis.chattywap.conversation.dto;

import com.adonis.chattywap.conversation.MemberRole;

import java.time.Instant;
import java.util.UUID;

/**
 * A member as the other members see them: no email address.
 */
public record ConversationMemberResponse(UUID userId, String displayName, MemberRole role, Instant joinedAt) {
}
