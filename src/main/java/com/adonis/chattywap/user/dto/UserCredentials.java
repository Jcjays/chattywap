package com.adonis.chattywap.user.dto;

import java.util.UUID;

/**
 * What the {@code auth} feature needs to check a login. Internal only: never return it from a controller.
 */
public record UserCredentials(UUID userId, String passwordHash) {

	// A record's generated toString() prints every component. Keep the hash out of logs.
	@Override
	public String toString() {
		return "UserCredentials[userId=" + userId + ", passwordHash=<hidden>]";
	}

}
