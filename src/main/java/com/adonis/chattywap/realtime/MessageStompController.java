package com.adonis.chattywap.realtime;

import com.adonis.chattywap.common.FieldViolation;
import com.adonis.chattywap.message.MessageService;
import com.adonis.chattywap.message.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;

/**
 * Sending messages through STOMP: the counterpart of {@code POST /api/v1/conversations/{conversationId}/messages},
 * with the same request body. Transport only: the sender is the connection's user, from the access token in its
 * CONNECT frame, and {@link MessageService} decides who may post, exactly as for REST.
 * <p>
 * Nothing is sent back from here. Once the message is stored and its transaction has committed,
 * {@link MessageBroadcaster} delivers it to the conversation's subscribers, the sender included.
 * <p>
 * A message that isn't stored is answered with a problem detail on {@code /user/queue/errors}, to the sending
 * connection only, with the same status and detail as the REST endpoint would give and the destination as
 * {@code instance}. The connection stays open. Nothing is stored or delivered in that case.
 */
@Controller
class MessageStompController {

	private static final Logger log = LoggerFactory.getLogger(MessageStompController.class);

	private static final String DESTINATION = SimpMessageHeaderAccessor.DESTINATION_HEADER;

	private final MessageService messageService;

	MessageStompController(MessageService messageService) {
		this.messageService = messageService;
	}

	/**
	 * {@link StompSecurityInterceptor} has already checked that the connection is authenticated and the destination
	 * names a conversation ID, so the ID always converts.
	 */
	@MessageMapping(WebSocketConfig.SEND_MESSAGE)
	void send(@DestinationVariable UUID conversationId, @Valid @Payload SendMessageRequest request,
			Authentication authentication) {
		messageService.sendMessage(UUID.fromString(authentication.getName()), conversationId, request.content());
	}

	/**
	 * Bean Validation failures: the same {@code errors} as REST. An empty payload is reported as a validation failure
	 * without field errors, which is the same as a payload that can't be read.
	 */
	@MessageExceptionHandler
	@SendToUser(destinations = WebSocketConfig.ERRORS_QUEUE, broadcast = false)
	ProblemDetail invalidPayload(MethodArgumentNotValidException ex, @Header(DESTINATION) String destination) {
		BindingResult bindingResult = ex.getBindingResult();
		if (bindingResult == null || !bindingResult.hasFieldErrors()) {
			return badPayload(destination);
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
		problem.setProperty("errors", FieldViolation.of(bindingResult));
		return ProblemDetailStompErrorHandler.forDestination(problem, destination);
	}

	/**
	 * Malformed JSON, or JSON that isn't an object. The exception's message quotes the payload, so it isn't passed on.
	 */
	@MessageExceptionHandler
	@SendToUser(destinations = WebSocketConfig.ERRORS_QUEUE, broadcast = false)
	ProblemDetail unreadablePayload(MessageConversionException ex, @Header(DESTINATION) String destination) {
		return badPayload(destination);
	}

	/**
	 * Rejections by the service, such as {@code ConversationNotFoundException}: the problem detail the REST endpoint
	 * would return.
	 */
	@MessageExceptionHandler
	@SendToUser(destinations = WebSocketConfig.ERRORS_QUEUE, broadcast = false)
	ProblemDetail rejected(ErrorResponseException ex, @Header(DESTINATION) String destination) {
		return ProblemDetailStompErrorHandler.forDestination(ex.getBody(), destination);
	}

	/**
	 * Anything else is a bug or an infrastructure failure, such as a message that couldn't be stored. The client gets
	 * a generic message; the details go to the server log only.
	 */
	@MessageExceptionHandler
	@SendToUser(destinations = WebSocketConfig.ERRORS_QUEUE, broadcast = false)
	ProblemDetail unexpected(Exception ex, @Header(DESTINATION) String destination) {
		log.error("Unexpected error while handling a STOMP message", ex);
		return ProblemDetailStompErrorHandler.forDestination(
				ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),
				destination);
	}

	private static ProblemDetail badPayload(String destination) {
		return ProblemDetailStompErrorHandler.forDestination(
				ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Failed to read message payload."),
				destination);
	}

}
