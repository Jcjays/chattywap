package com.adonis.chattywap.conversation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param userIds the users to add. Users who are already members are skipped.
 */
public record AddMembersRequest(@NotEmpty @Size(max = 100) List<@NotNull UUID> userIds) {
}
