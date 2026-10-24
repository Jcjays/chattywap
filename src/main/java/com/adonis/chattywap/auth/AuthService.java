package com.adonis.chattywap.auth;

import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserCredentials;
import com.adonis.chattywap.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Registration, login, token refresh and logout.
 * <p>
 * This class has no {@code @Transactional}: password hashing is deliberately slow (about 0.1 s) and shouldn't
 * hold a database connection. The services it calls open their own short transactions.
 */
@Service
public class AuthService {

	/** BCrypt ignores everything after the first 72 bytes, and Spring Security refuses to hash longer input. */
	private static final int MAX_PASSWORD_BYTES = 72;

	private static final String TOKEN_TYPE = "Bearer";

	private final UserService userService;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenService accessTokenService;

	private final RefreshTokenService refreshTokenService;

	private final AuthProperties properties;

	/** Hash of a random password nobody knows. Compared against when a login names an unknown email. */
	private final String unknownUserPasswordHash;

	AuthService(UserService userService, PasswordEncoder passwordEncoder, AccessTokenService accessTokenService,
			RefreshTokenService refreshTokenService, AuthProperties properties) {
		this.userService = userService;
		this.passwordEncoder = passwordEncoder;
		this.accessTokenService = accessTokenService;
		this.refreshTokenService = refreshTokenService;
		this.properties = properties;
		this.unknownUserPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * @throws com.adonis.chattywap.user.EmailAlreadyRegisteredException if the email is taken
	 */
	public UserResponse register(RegisterRequest request) {
		// @Size(max = 72) counts characters, but a character can take up to 4 bytes in UTF-8.
		if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new PasswordTooLongException(MAX_PASSWORD_BYTES);
		}
		// BCrypt adds a random salt, so two users with the same password get different hashes.
		String passwordHash = passwordEncoder.encode(request.password());
		return userService.createUser(request.email(), passwordHash, request.displayName());
	}

	/**
	 * @throws InvalidCredentialsException if the email is unknown or the password is wrong
	 */
	public TokenResponse login(LoginRequest request) {
		Optional<UserCredentials> credentials = userService.findCredentialsByEmail(request.email());

		// Check a password even when no account has this email. Otherwise unknown emails would be answered
		// noticeably faster than wrong passwords, and the response time would reveal which emails exist.
		String passwordHash = credentials.map(UserCredentials::passwordHash).orElse(unknownUserPasswordHash);
		boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

		if (credentials.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}

		UUID userId = credentials.get().userId();
		String refreshToken = refreshTokenService.startFamily(userId);
		return tokenResponse(userId, refreshToken);
	}

	/**
	 * @throws InvalidRefreshTokenException if the refresh token can't be used
	 */
	public TokenResponse refresh(String refreshToken) {
		RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(refreshToken);
		return tokenResponse(rotated.userId(), rotated.refreshToken());
	}

	/**
	 * Revokes the refresh token's session. Access tokens already issued stay valid until they expire.
	 */
	public void logout(String refreshToken) {
		refreshTokenService.revokeFamily(refreshToken);
	}

	private TokenResponse tokenResponse(UUID userId, String refreshToken) {
		return new TokenResponse(
				accessTokenService.issue(userId),
				TOKEN_TYPE,
				properties.accessTokenTtl().toSeconds(),
				refreshToken,
				properties.refreshTokenTtl().toSeconds());
	}

}
