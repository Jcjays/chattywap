package com.adonis.chattywap.conversation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateDirectConversationRequest(@NotNull UUID recipientId) {
}
