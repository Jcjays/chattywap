package com.adonis.chattywap.message.dto;

import java.util.UUID;

/**
 * @param unreadCount how many messages of the conversation others sent after the caller's read position; all messages
 *                    others sent if the caller hasn't reported reading any
 */
public record UnreadCountResponse(UUID conversationId, long unreadCount) {
}
