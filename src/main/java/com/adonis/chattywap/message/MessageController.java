package com.adonis.chattywap.message;

import com.adonis.chattywap.message.dto.MessagePageResponse;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Message endpoints of a conversation. They require an access token, like every endpoint except authentication and
 * health. Transport only: the sender is the caller from the token, and {@link MessageService} decides who may post
 * and read.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
class MessageController {

	private final MessageService messageService;

	MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	MessageResponse send(@PathVariable UUID conversationId, @Valid @RequestBody SendMessageRequest request,
			Authentication authentication) {
		return messageService.sendMessage(currentUserId(authentication), conversationId, request.content());
	}

	/**
	 * The same parameters as the conversation list: Spring Data binds {@code ?page=0&size=20} to the
	 * {@link Pageable}, capping the size at {@code spring.data.web.pageable.max-page-size}. The order is fixed, so a
	 * {@code sort} parameter is ignored. With a {@code cursor}, the page starts after it and {@code page} is ignored.
	 * An empty cursor counts as none.
	 */
	@GetMapping
	MessagePageResponse list(@PathVariable UUID conversationId, Pageable pageable,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		UUID callerId = currentUserId(authentication);
		if (cursor == null || cursor.isEmpty()) {
			return messageService.listMessages(callerId, conversationId, pageable.getPageNumber(),
					pageable.getPageSize());
		}
		return messageService.listMessagesAfter(callerId, conversationId, cursor, pageable.getPageSize());
	}

	/**
	 * The access token's subject, which is the user ID. Never a value from the request path or body.
	 */
	private static UUID currentUserId(Authentication authentication) {
		return UUID.fromString(authentication.getName());
	}

}
