package com.adonis.chattywap.user.dto;

import java.util.UUID;

/**
 * What other users may see of a user, for example the members of a shared conversation. Unlike
 * {@link UserResponse}, it has no email address.
 */
public record UserSummary(UUID id, String displayName) {
}
