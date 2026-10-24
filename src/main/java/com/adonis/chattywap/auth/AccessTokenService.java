package com.adonis.chattywap.auth;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues short-lived access tokens. Validation is done by Spring Security with the {@code JwtDecoder}
 * from {@link JwtConfig}.
 */
@Service
class AccessTokenService {

	private final JwtEncoder jwtEncoder;

	private final AuthProperties properties;

	AccessTokenService(JwtEncoder jwtEncoder, AuthProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	/**
	 * Creates a signed JWT. Its subject ({@code sub}) is the user ID, which never changes, unlike the email.
	 */
	public String issue(UUID userId) {
		Instant issuedAt = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.id(UUID.randomUUID().toString())
				.issuer(properties.jwtIssuer())
				.audience(List.of(properties.jwtAudience()))
				.subject(userId.toString())
				.issuedAt(issuedAt)
				.expiresAt(issuedAt.plus(properties.accessTokenTtl()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
