package com.adonis.chattywap.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param name      stored without surrounding whitespace
 * @param memberIds the users to add besides the creator, who is always added as administrator. May be empty.
 *                  Duplicates and the creator's own ID are ignored.
 */
public record CreateGroupConversationRequest(
		@NotBlank @Size(max = 100) String name,
		@NotNull @Size(max = 100) List<@NotNull UUID> memberIds) {
}
