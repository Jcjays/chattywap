package com.adonis.chattywap.conversation;

import com.adonis.chattywap.conversation.dto.AddMembersRequest;
import com.adonis.chattywap.conversation.dto.ConversationPageResponse;
import com.adonis.chattywap.conversation.dto.ConversationResponse;
import com.adonis.chattywap.conversation.dto.CreateDirectConversationRequest;
import com.adonis.chattywap.conversation.dto.CreateGroupConversationRequest;
import com.adonis.chattywap.conversation.dto.DirectConversationResult;
import com.adonis.chattywap.conversation.dto.RenameGroupRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Conversation endpoints. They all require an access token: {@code SecurityConfig} only lets authentication
 * and health requests through without one. Transport only: the caller's ID comes from the token, and
 * {@link ConversationService} decides what the caller may see and do.
 */
@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController {

	private final ConversationService conversationService;

	ConversationController(ConversationService conversationService) {
		this.conversationService = conversationService;
	}

	/**
	 * 201 if this request created the conversation, 200 if the two users already had one.
	 */
	@PostMapping("/direct")
	ResponseEntity<ConversationResponse> createDirect(@Valid @RequestBody CreateDirectConversationRequest request,
			Authentication authentication) {
		DirectConversationResult result =
				conversationService.createDirectConversation(currentUserId(authentication), request.recipientId());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.conversation());
	}

	@PostMapping("/group")
	@ResponseStatus(HttpStatus.CREATED)
	ConversationResponse createGroup(@Valid @RequestBody CreateGroupConversationRequest request,
			Authentication authentication) {
		return conversationService.createGroupConversation(currentUserId(authentication), request.name(),
				request.memberIds());
	}

	/**
	 * Spring Data binds {@code ?page=0&size=20} to the {@link Pageable}; the size is capped at
	 * {@code spring.data.web.pageable.max-page-size}. The order is fixed, so a {@code sort} parameter is ignored.
	 * With a {@code cursor}, the page starts after it and {@code page} is ignored. An empty cursor counts as none,
	 * for clients that always send the parameter.
	 */
	@GetMapping
	ConversationPageResponse list(Pageable pageable, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		UUID callerId = currentUserId(authentication);
		if (cursor == null || cursor.isEmpty()) {
			return conversationService.listConversations(callerId, pageable.getPageNumber(), pageable.getPageSize());
		}
		return conversationService.listConversationsAfter(callerId, cursor, pageable.getPageSize());
	}

	@GetMapping("/{conversationId}")
	ConversationResponse get(@PathVariable UUID conversationId, Authentication authentication) {
		return conversationService.getConversation(currentUserId(authentication), conversationId);
	}

	@PutMapping("/{conversationId}/name")
	ConversationResponse rename(@PathVariable UUID conversationId, @Valid @RequestBody RenameGroupRequest request,
			Authentication authentication) {
		return conversationService.renameGroup(currentUserId(authentication), conversationId, request.name());
	}

	@DeleteMapping("/{conversationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID conversationId, Authentication authentication) {
		conversationService.deleteGroup(currentUserId(authentication), conversationId);
	}

	@PostMapping("/{conversationId}/members")
	ConversationResponse addMembers(@PathVariable UUID conversationId, @Valid @RequestBody AddMembersRequest request,
			Authentication authentication) {
		return conversationService.addMembers(currentUserId(authentication), conversationId, request.userIds());
	}

	@DeleteMapping("/{conversationId}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeMember(@PathVariable UUID conversationId, @PathVariable UUID userId, Authentication authentication) {
		conversationService.removeMember(currentUserId(authentication), conversationId, userId);
	}

	@PostMapping("/{conversationId}/members/{userId}/promote")
	ConversationResponse promote(@PathVariable UUID conversationId, @PathVariable UUID userId,
			Authentication authentication) {
		return conversationService.promoteToAdmin(currentUserId(authentication), conversationId, userId);
	}

	@PostMapping("/{conversationId}/members/{userId}/demote")
	ConversationResponse demote(@PathVariable UUID conversationId, @PathVariable UUID userId,
			Authentication authentication) {
		return conversationService.demoteToMember(currentUserId(authentication), conversationId, userId);
	}

	@PostMapping("/{conversationId}/leave")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void leave(@PathVariable UUID conversationId, Authentication authentication) {
		conversationService.leave(currentUserId(authentication), conversationId);
	}

	/**
	 * The access token's subject, which is the user ID (see {@code UserController#me}). Never a value from the
	 * request path or body.
	 */
	private static UUID currentUserId(Authentication authentication) {
		return UUID.fromString(authentication.getName());
	}

}
