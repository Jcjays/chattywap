package com.adonis.chattywap.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param name the same rules as when creating a group; stored without surrounding whitespace
 */
public record RenameGroupRequest(@NotBlank @Size(max = 100) String name) {
}
