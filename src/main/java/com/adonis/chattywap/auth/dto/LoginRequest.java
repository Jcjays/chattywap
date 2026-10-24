package com.adonis.chattywap.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No email-format check here: a malformed email simply doesn't match an account. The size limits only stop
 * oversized input.
 */
public record LoginRequest(
		@NotBlank @Size(max = 254) String email,
		@NotBlank @Size(max = 72) String password) {

	// Keep the password out of logs.
	@Override
	public String toString() {
		return "LoginRequest[email=" + email + ", password=<hidden>]";
	}

}
