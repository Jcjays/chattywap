package com.adonis.chattywap.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

	List<ConversationMember> findByConversationId(UUID conversationId);

	boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

	long countByUserId(UUID userId);

}
