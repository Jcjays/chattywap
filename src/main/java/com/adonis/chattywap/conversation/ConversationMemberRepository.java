package com.adonis.chattywap.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

	List<ConversationMember> findByConversationId(UUID conversationId);

	boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

	/**
	 * Removes all members of the conversation in one statement. Members already loaded stay in the persistence
	 * context; callers must not use them afterwards.
	 */
	@Modifying
	@Query("delete from ConversationMember m where m.conversationId = :conversationId")
	int deleteByConversationId(UUID conversationId);

}
