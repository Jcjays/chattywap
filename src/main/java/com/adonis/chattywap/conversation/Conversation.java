package com.adonis.chattywap.conversation;

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
 * JPA mapping of the {@code conversations} table. Package-private: the entity never leaves the
 * {@code conversation} feature.
 * <p>
 * Members are stored in {@code conversation_members} ({@link ConversationMember}) and referenced by the
 * conversation's ID rather than mapped as a collection here, so loading a conversation never loads its
 * members by accident.
 */
@Entity
@Table(name = "conversations")
class Conversation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	private ConversationType type;

	@Column(name = "name")
	private String name;

	// Users are referenced by ID, not by a JPA association: the User entity belongs to the user feature.
	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "direct_user1_id", updatable = false)
	private UUID directUser1Id;

	@Column(name = "direct_user2_id", updatable = false)
	private UUID directUser2Id;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// Groups only: set when the group is deleted, explicitly or because its last member left. Deleted groups have
	// no members and are never shown again.
	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected Conversation() {
	}

	private Conversation(ConversationType type, String name, UUID createdBy, DirectPair pair, Instant now) {
		this.type = type;
		this.name = name;
		this.createdBy = createdBy;
		this.directUser1Id = pair == null ? null : pair.user1Id();
		this.directUser2Id = pair == null ? null : pair.user2Id();
		this.createdAt = now;
		this.updatedAt = now;
	}

	static Conversation direct(DirectPair pair, UUID createdBy, Instant now) {
		return new Conversation(ConversationType.DIRECT, null, createdBy, pair, now);
	}

	static Conversation group(String name, UUID createdBy, Instant now) {
		return new Conversation(ConversationType.GROUP, name, createdBy, null, now);
	}

	boolean isGroup() {
		return type == ConversationType.GROUP;
	}

	boolean isDeleted() {
		return deletedAt != null;
	}

	/**
	 * For a direct conversation: the participant who isn't {@code userId}.
	 */
	UUID otherDirectUser(UUID userId) {
		return userId.equals(directUser1Id) ? directUser2Id : directUser1Id;
	}

	/**
	 * Records activity in the conversation: a change to it, for example to its members, or a new message.
	 * Conversation lists are ordered by this time, most recent first.
	 */
	void touch(Instant now) {
		this.updatedAt = now;
	}

	/**
	 * Groups only. {@code name} is already stripped of surrounding whitespace.
	 */
	void rename(String name, Instant now) {
		this.name = name;
		touch(now);
	}

	/**
	 * Groups only. The caller removes the memberships in the same transaction.
	 */
	void markDeleted(Instant now) {
		this.deletedAt = now;
		touch(now);
	}

	UUID getId() {
		return id;
	}

	ConversationType getType() {
		return type;
	}

	String getName() {
		return name;
	}

	UUID getCreatedBy() {
		return createdBy;
	}

	UUID getDirectUser1Id() {
		return directUser1Id;
	}

	UUID getDirectUser2Id() {
		return directUser2Id;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

}
