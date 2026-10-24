package com.adonis.chattywap.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's public profile. Never contains the password hash.
 */
public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {
}
