package com.adonis.chattywap.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code /refresh} and {@code /logout}. Issued tokens are 43 characters long; the size limit only
 * stops oversized input.
 */
public record RefreshTokenRequest(@NotBlank @Size(max = 128) String refreshToken) {

	// Keep the token out of logs.
	@Override
	public String toString() {
		return "RefreshTokenRequest[refreshToken=<hidden>]";
	}

}
