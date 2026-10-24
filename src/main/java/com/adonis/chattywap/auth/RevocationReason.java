package com.adonis.chattywap.auth;

/**
 * Why a refresh-token family (a login session) was revoked. Stored by name in
 * {@code refresh_token_families.revoked_reason}.
 */
enum RevocationReason {

	/** The client logged out. */
	LOGOUT,

	/** A refresh token that had already been rotated was presented again, so it may have been stolen. */
	REUSE_DETECTED

}
