package com.adonis.chattywap.conversation.dto;

/**
 * Result of asking for a direct conversation. Internal only: the controller returns just the conversation.
 *
 * @param created {@code true} if this request created the conversation, {@code false} if it already existed
 */
public record DirectConversationResult(ConversationResponse conversation, boolean created) {
}
