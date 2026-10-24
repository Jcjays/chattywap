package com.adonis.chattywap.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 * <p>
 * A refresh token is a random string, not a JWT. It is only useful together with the database row that
 * records its hash, so the server can revoke it at any time. Each successful refresh <em>rotates</em> the
 * token: the presented token is revoked and a new one is returned. A token can therefore succeed only once.
 */
@Service
class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	/** 32 random bytes: 256 bits, far too many to guess. */
	private static final int TOKEN_BYTES = 32;

	private final RefreshTokenFamilyRepository familyRepository;

	private final RefreshTokenRepository tokenRepository;

	private final AuthProperties properties;

	// Draws from the operating system's cryptographically secure source. java.util.Random is predictable
	// and must never be used for secrets.
	private final SecureRandom secureRandom = new SecureRandom();

	RefreshTokenService(RefreshTokenFamilyRepository familyRepository, RefreshTokenRepository tokenRepository,
			AuthProperties properties) {
		this.familyRepository = familyRepository;
		this.tokenRepository = tokenRepository;
		this.properties = properties;
	}

	/**
	 * Starts a new token family (one login session) and returns its first refresh token.
	 */
	@Transactional
	public String startFamily(UUID userId) {
		Instant now = Instant.now();
		RefreshTokenFamily family = familyRepository.save(new RefreshTokenFamily(userId, now));
		return issueToken(family.getId(), now);
	}

	/**
	 * Exchanges a valid refresh token for a new one, revoking the old token in the same transaction.
	 * <p>
	 * {@code noRollbackFor}: by default, an exception thrown out of a {@code @Transactional} method rolls the
	 * transaction back. When a reused token is detected, the family revocation must be committed even though
	 * the request fails, so this exception commits instead.
	 *
	 * @throws InvalidRefreshTokenException if the token is unknown, expired, already used or its session is revoked
	 */
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public RotatedToken rotate(String rawToken) {
		String tokenHash = hash(rawToken);
		RefreshTokenFamily family = familyRepository.findByTokenHashForUpdate(tokenHash)
				.orElseThrow(InvalidRefreshTokenException::new);
		// Read the token only after the family is locked. A concurrent refresh of the same token has then
		// either committed (and we see the token as revoked) or not started.
		RefreshToken token = tokenRepository.findByTokenHash(tokenHash)
				.orElseThrow(InvalidRefreshTokenException::new);
		Instant now = Instant.now();

		if (family.isRevoked()) {
			throw new InvalidRefreshTokenException();
		}
		if (token.isRevoked()) {
			// The token was already exchanged for a newer one, yet it is presented again. Either the client
			// sent it twice or someone copied it. We can't tell which, so end the whole session: whoever
			// holds the newest token loses it too, and the real user has to log in again.
			family.revoke(RevocationReason.REUSE_DETECTED, now);
			log.warn("Refresh token reuse detected: revoked token family {} of user {}",
					family.getId(), family.getUserId());
			throw new InvalidRefreshTokenException();
		}
		if (token.isExpiredAt(now)) {
			throw new InvalidRefreshTokenException();
		}

		token.revoke(now);
		String newRawToken = issueToken(family.getId(), now);
		return new RotatedToken(family.getUserId(), newRawToken);
	}

	/**
	 * Logs out: revokes the token's family, so neither this token nor any other token of the session works
	 * again. Does nothing if the token is unknown or the session is already revoked, so logging out twice
	 * is harmless.
	 */
	@Transactional
	public void revokeFamily(String rawToken) {
		String tokenHash = hash(rawToken);
		Optional<RefreshTokenFamily> family = familyRepository.findByTokenHashForUpdate(tokenHash);
		if (family.isEmpty() || family.get().isRevoked()) {
			return;
		}
		Instant now = Instant.now();
		family.get().revoke(RevocationReason.LOGOUT, now);
		tokenRepository.findByTokenHash(tokenHash)
				.filter(token -> !token.isRevoked())
				.ifPresent(token -> token.revoke(now));
	}

	private String issueToken(UUID familyId, Instant now) {
		String rawToken = generateRawToken();
		Instant expiresAt = now.plus(properties.refreshTokenTtl());
		tokenRepository.save(new RefreshToken(familyId, hash(rawToken), now, expiresAt));
		return rawToken;
	}

	private String generateRawToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		// URL-safe Base64 without padding: 43 characters that need no escaping in JSON, URLs or headers.
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * SHA-256, not BCrypt. Passwords need a slow, salted hash because people choose guessable ones. A refresh
	 * token is 256 random bits, so a fast hash is safe, and because it is unsalted the same token always gives
	 * the same hash, which lets us look the token up by it.
	 */
	static String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// Every Java runtime must support SHA-256, so this can't happen.
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	record RotatedToken(UUID userId, String refreshToken) {

		// Keep the raw token out of logs.
		@Override
		public String toString() {
			return "RotatedToken[userId=" + userId + ", refreshToken=<hidden>]";
		}

	}

}
