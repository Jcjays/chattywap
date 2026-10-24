package com.adonis.chattywap.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Authentication settings, bound from the {@code chattywap.auth.*} properties in application.yaml.
 * <p>
 * Spring Boot creates the record through its constructor, so the values can't change after startup.
 * {@code @Validated} makes startup fail with a clear message when a required value is missing.
 *
 * @param jwtSecret       Base64-encoded HMAC key for signing access tokens (at least 32 bytes once decoded)
 * @param jwtIssuer       value of the {@code iss} claim; tokens with another issuer are rejected
 * @param jwtAudience     value of the {@code aud} claim; tokens for another audience are rejected
 * @param accessTokenTtl  lifetime of an access token
 * @param refreshTokenTtl lifetime of a refresh token, counted from when it was issued
 */
@ConfigurationProperties("chattywap.auth")
@Validated
record AuthProperties(
		@NotBlank String jwtSecret,
		@NotBlank String jwtIssuer,
		@NotBlank String jwtAudience,
		@NotNull Duration accessTokenTtl,
		@NotNull Duration refreshTokenTtl) {

	// A record's generated toString() prints every component. Keep the signing key out of logs.
	@Override
	public String toString() {
		return "AuthProperties[jwtSecret=<hidden>, jwtIssuer=" + jwtIssuer + ", jwtAudience=" + jwtAudience
				+ ", accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl=" + refreshTokenTtl + "]";
	}

}
