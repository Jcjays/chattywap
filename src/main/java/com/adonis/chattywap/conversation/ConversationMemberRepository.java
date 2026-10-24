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
	 * Whether the user is a member of the conversation and it isn't deleted. Deleted groups have no members, so the
	 * join only matters if that ever fails to hold.
	 */
	@Query("""
			select count(*) > 0 from ConversationMember m
			join Conversation c on c.id = m.conversationId
			where m.conversationId = :conversationId and m.userId = :userId and c.deletedAt is null
			""")
	boolean existsInActiveConversation(UUID conversationId, UUID userId);

	/**
	 * Removes all members of the conversation in one statement. Members already loaded stay in the persistence
	 * context; callers must not use them afterwards.
	 */
	@Modifying
	@Query("delete from ConversationMember m where m.conversationId = :conversationId")
	int deleteByConversationId(UUID conversationId);

}
