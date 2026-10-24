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
 * One user's membership in one conversation. The database allows at most one row per conversation and user.
 */
@Entity
@Table(name = "conversation_members")
class ConversationMember {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private MemberRole role;

	@Column(name = "joined_at", nullable = false, updatable = false)
	private Instant joinedAt;

	protected ConversationMember() {
	}

	ConversationMember(UUID conversationId, UUID userId, MemberRole role, Instant joinedAt) {
		this.conversationId = conversationId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = joinedAt;
	}

	boolean isAdmin() {
		return role == MemberRole.ADMIN;
	}

	void promoteToAdmin() {
		this.role = MemberRole.ADMIN;
	}

	void demoteToMember() {
		this.role = MemberRole.MEMBER;
	}

	UUID getId() {
		return id;
	}

	UUID getConversationId() {
		return conversationId;
	}

	UUID getUserId() {
		return userId;
	}

	MemberRole getRole() {
		return role;
	}

	Instant getJoinedAt() {
		return joinedAt;
	}

}
