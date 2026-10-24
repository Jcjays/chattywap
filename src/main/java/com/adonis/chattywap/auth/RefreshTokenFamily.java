package com.adonis.chattywap.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One login session. Logging in creates a family; every refresh token issued for that session belongs to it.
 * Revoking the family ends the session: none of its tokens can be used any more.
 * <p>
 * The family row is also the lock for its session: every refresh and logout locks it first
 * (see {@link RefreshTokenFamilyRepository#findByTokenHashForUpdate}), so the operations of one session
 * never interleave.
 */
@Entity
@Table(name = "refresh_token_families")
class RefreshTokenFamily {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	// The user is referenced by ID, not by a JPA association: the User entity belongs to the user feature.
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoked_reason")
	private RevocationReason revokedReason;

	protected RefreshTokenFamily() {
	}

	RefreshTokenFamily(UUID userId, Instant createdAt) {
		this.userId = userId;
		this.createdAt = createdAt;
	}

	boolean isRevoked() {
		return revokedAt != null;
	}

	/**
	 * Changes the entity only. Hibernate notices the change ("dirty checking") and writes it when the
	 * transaction commits, so there is no explicit save call.
	 */
	void revoke(RevocationReason reason, Instant now) {
		this.revokedAt = now;
		this.revokedReason = reason;
	}

	UUID getId() {
		return id;
	}

	UUID getUserId() {
		return userId;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getRevokedAt() {
		return revokedAt;
	}

	RevocationReason getRevokedReason() {
		return revokedReason;
	}

}
