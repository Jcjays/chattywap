package com.adonis.chattywap.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * JPA mapping of the {@code users} table. Package-private: the entity never leaves the {@code user} feature.
 */
@Entity
@Table(name = "users")
class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "email", nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Required by JPA, which creates the object first and then fills the fields from the database row.
	 */
	protected User() {
	}

	User(String email, String passwordHash, String displayName) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
	}

	/**
	 * JPA lifecycle callback: Hibernate calls it just before inserting the row.
	 */
	@PrePersist
	void onCreate() {
		// PostgreSQL stores microseconds. Truncating here keeps the value returned right after
		// registration identical to the value read back from the database later.
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * JPA lifecycle callback: Hibernate calls it just before updating a changed row.
	 */
	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	UUID getId() {
		return id;
	}

	String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	String getDisplayName() {
		return displayName;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

}
