package com.adonis.chattywap.auth.dto;

/**
 * Returned by login and refresh.
 *
 * @param accessToken           JWT to send as {@code Authorization: Bearer <accessToken>}
 * @param tokenType             always {@code Bearer}
 * @param expiresIn             seconds until the access token expires
 * @param refreshToken          single-use token for {@code /api/v1/auth/refresh}; each refresh returns a new one
 * @param refreshTokenExpiresIn seconds until the refresh token expires
 */
public record TokenResponse(
		String accessToken,
		String tokenType,
		long expiresIn,
		String refreshToken,
		long refreshTokenExpiresIn) {

	// Keep the tokens out of logs.
	@Override
	public String toString() {
		return "TokenResponse[accessToken=<hidden>, tokenType=" + tokenType + ", expiresIn=" + expiresIn
				+ ", refreshToken=<hidden>, refreshTokenExpiresIn=" + refreshTokenExpiresIn + "]";
	}

}
