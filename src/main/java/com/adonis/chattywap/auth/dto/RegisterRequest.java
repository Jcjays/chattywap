package com.adonis.chattywap.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Bean Validation constraints are checked by Spring MVC when the controller parameter is marked {@code @Valid}.
 * <p>
 * Password rules follow NIST SP 800-63B: a minimum length and no composition rules. The maximum of 72 is
 * BCrypt's input limit; {@code AuthService} also checks it in bytes, because non-ASCII characters take more
 * than one byte.
 */
public record RegisterRequest(
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank @Size(max = 100) String displayName) {

	// Keep the password out of logs.
	@Override
	public String toString() {
		return "RegisterRequest[email=" + email + ", password=<hidden>, displayName=" + displayName + "]";
	}

}
