package com.adonis.chattywap.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit test: builds the encoder and decoder from {@link JwtConfig} directly, without Spring.
 */
class AccessTokenServiceTest {

	private static final String ISSUER = "chattywap";

	private static final String AUDIENCE = "chattywap-api";

	private final AuthProperties properties = properties(base64("unit-test-signing-key-0123456789abcdef"));

	private final JwtConfig jwtConfig = new JwtConfig();

	private final JwtEncoder encoder = jwtConfig.jwtEncoder(properties);

	private final JwtDecoder decoder = jwtConfig.jwtDecoder(properties);

	private final AccessTokenService accessTokenService = new AccessTokenService(encoder, properties);

	@Test
	void issuedTokenHasUserIdAsSubjectAndConfiguredClaims() {
		UUID userId = UUID.randomUUID();

		Jwt jwt = decoder.decode(accessTokenService.issue(userId));

		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
		assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
		assertThat(jwt.getId()).isNotBlank();
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void rejectsTokenSignedWithAnotherKey() {
		AuthProperties otherKey = properties(base64("another-signing-key-0123456789abcdef"));
		String token = new AccessTokenService(jwtConfig.jwtEncoder(otherKey), otherKey).issue(UUID.randomUUID());

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(BadJwtException.class)
				.hasMessageContaining("Invalid signature");
	}

	@Test
	void rejectsTamperedToken() {
		String token = accessTokenService.issue(UUID.randomUUID());
		String[] parts = token.split("\\.");
		String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
				"{\"sub\":\"someone-else\"}".getBytes(StandardCharsets.UTF_8));
		String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

		assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsExpiredToken() {
		// Beyond the decoder's default clock-skew allowance of 60 seconds.
		Instant issuedAt = Instant.now().minus(Duration.ofMinutes(20));
		String token = token(claims -> claims.issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15))));

		assertThatThrownBy(() -> decoder.decode(token))
				.isInstanceOf(JwtValidationException.class)
				.hasMessageContaining("expired");
	}

	@Test
	void rejectsTokenFromAnotherIssuer() {
		String token = token(claims -> claims.issuer("someone-else"));

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
	}

	@Test
	void rejectsTokenForAnotherAudience() {
		String token = token(claims -> claims.audience(List.of("another-api")));

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
	}

	@Test
	void acceptsWellFormedTokenBuiltByTheTestHelper() {
		// Guards the tests above: they fail because of the one claim they change, not because of the helper.
		assertThat(decoder.decode(token(claims -> {
		})).getSubject()).isNotBlank();
	}

	@Test
	void refusesSecretShorterThan32Bytes() {
		AuthProperties shortKey = properties(base64("too-short"));

		assertThatThrownBy(() -> JwtConfig.signingKey(shortKey))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32 bytes");
	}

	@Test
	void refusesSecretThatIsNotBase64() {
		AuthProperties notBase64 = properties("change-me");

		assertThatThrownBy(() -> JwtConfig.signingKey(notBase64))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not valid Base64")
				.hasNoCause();
	}

	@Test
	void toStringHidesTheSecret() {
		assertThat(properties.toString()).doesNotContain(properties.jwtSecret()).contains("<hidden>");
	}

	/**
	 * Encodes a valid token, then lets the test change one claim.
	 */
	private String token(Consumer<JwtClaimsSet.Builder> customizer) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.audience(List.of(AUDIENCE))
				.subject(UUID.randomUUID().toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(15)));
		customizer.accept(claims);
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	private static AuthProperties properties(String secret) {
		return new AuthProperties(secret, ISSUER, AUDIENCE, Duration.ofMinutes(15), Duration.ofDays(30));
	}

	private static String base64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

}
