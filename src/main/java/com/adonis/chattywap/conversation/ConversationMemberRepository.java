package com.adonis.chattywap.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
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
	 * Like {@link #existsInActiveConversation}, and locks the membership row until the transaction ends, so it
	 * can't be deleted in the meantime. Returns the membership's ID, or nothing if the user isn't a member.
	 * <p>
	 * {@code FOR KEY SHARE} is the weakest row lock: it conflicts only with deleting the row (or changing its ID),
	 * not with updating its role. JPA's lock modes have no equivalent, hence the native query.
	 */
	@Query(value = """
			select m.id from conversation_members m
			join conversations c on c.id = m.conversation_id
			where m.conversation_id = :conversationId and m.user_id = :userId and c.deleted_at is null
			for key share of m
			""", nativeQuery = true)
	Optional<UUID> lockMembershipInActiveConversation(UUID conversationId, UUID userId);

	/**
	 * Removes all members of the conversation in one statement. Members already loaded stay in the persistence
	 * context; callers must not use them afterwards.
	 */
	@Modifying
	@Query("delete from ConversationMember m where m.conversationId = :conversationId")
	int deleteByConversationId(UUID conversationId);

}
