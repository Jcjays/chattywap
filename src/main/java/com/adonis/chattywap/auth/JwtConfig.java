package com.adonis.chattywap.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Creates and checks access tokens: JWTs signed with HMAC-SHA256 (HS256) using one shared secret key.
 * <p>
 * The same application issues and validates the tokens, so a shared secret is enough. If other services
 * ever need to validate tokens, switch to an RSA or EC key pair so they only get the public key.
 * <p>
 * Spring Security's resource server support uses the {@link JwtDecoder} bean to authenticate every request
 * that carries an {@code Authorization: Bearer ...} header (see {@link SecurityConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfig {

	/** HS256 needs a key at least as long as its output: 256 bits. */
	private static final int MIN_SECRET_BYTES = 32;

	@Bean
	JwtEncoder jwtEncoder(AuthProperties properties) {
		return NimbusJwtEncoder.withSecretKey(signingKey(properties))
				.algorithm(MacAlgorithm.HS256)
				.build();
	}

	@Bean
	JwtDecoder jwtDecoder(AuthProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// The decoder always verifies the signature. The validators then check the claims: the defaults
		// reject expired tokens (exp, nbf) and unexpected token types; we add the issuer and the audience.
		decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
				new JwtIssuerValidator(properties.jwtIssuer()),
				new JwtAudienceValidator(properties.jwtAudience())));
		return decoder;
	}

	static SecretKey signingKey(AuthProperties properties) {
		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(properties.jwtSecret().strip());
		}
		catch (IllegalArgumentException ex) {
			// Don't chain the cause: its message quotes part of the secret.
			throw new IllegalStateException("chattywap.auth.jwt-secret (JWT_SECRET) is not valid Base64.");
		}
		if (keyBytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("chattywap.auth.jwt-secret (JWT_SECRET) must decode to at least "
					+ MIN_SECRET_BYTES + " bytes. Generate one with: openssl rand -base64 32");
		}
		return new SecretKeySpec(keyBytes, "HmacSHA256");
	}

}
