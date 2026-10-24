package com.adonis.chattywap.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored refresh token. Only the SHA-256 hash of the token is kept: someone who reads the database
 * still can't use the tokens.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected RefreshToken() {
	}

	RefreshToken(UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt) {
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	boolean isRevoked() {
		return revokedAt != null;
	}

	boolean isExpiredAt(Instant now) {
		return !now.isBefore(expiresAt);
	}

	void revoke(Instant now) {
		this.revokedAt = now;
	}

	UUID getId() {
		return id;
	}

	UUID getFamilyId() {
		return familyId;
	}

	String getTokenHash() {
		return tokenHash;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getExpiresAt() {
		return expiresAt;
	}

	Instant getRevokedAt() {
		return revokedAt;
	}

}
