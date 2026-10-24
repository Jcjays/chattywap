package com.adonis.chattywap.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashingTest {

	private static final String PASSWORD = "correct horse battery staple";

	private final PasswordEncoder passwordEncoder = new SecurityConfig().passwordEncoder();

	@Test
	void hashesWithBcryptAndNeverKeepsThePlaintext() {
		String hash = passwordEncoder.encode(PASSWORD);

		assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
	}

	@Test
	void verifiesTheRightPasswordOnly() {
		String hash = passwordEncoder.encode(PASSWORD);

		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
		assertThat(passwordEncoder.matches("correct horse battery stapler", hash)).isFalse();
	}

	@Test
	void saltsEveryHash() {
		assertThat(passwordEncoder.encode(PASSWORD)).isNotEqualTo(passwordEncoder.encode(PASSWORD));
	}

}
