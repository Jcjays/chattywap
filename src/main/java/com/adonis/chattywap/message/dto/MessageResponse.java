package com.adonis.chattywap.message.dto;

import com.adonis.chattywap.user.dto.UserSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * @param sender    who sent the message: their ID and display name, never their email address. Also for senders
 *                  who have left the conversation since.
 * @param content   exactly as sent
 * @param createdAt when the server stored the message, in microseconds
 */
public record MessageResponse(
		UUID id,
		UUID conversationId,
		UserSummary sender,
		String content,
		Instant createdAt) {
}
