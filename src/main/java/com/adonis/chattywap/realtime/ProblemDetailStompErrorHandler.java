package com.adonis.chattywap.realtime;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes STOMP ERROR frames with an RFC 9457 problem-detail body ({@code content-type: application/problem+json}),
 * the same format as REST errors. The frame's {@code message} header repeats the problem's {@code detail} for clients
 * that only read headers. If the rejected frame asked for a receipt, the ERROR frame carries its {@code receipt-id}.
 * <p>
 * After an ERROR frame the server closes the connection, as STOMP requires.
 * <p>
 * Spring calls {@link #handleClientMessageProcessingError} for frames its STOMP layer rejects before any interceptor
 * sees them: frames that can't be parsed, frames before CONNECT, a second CONNECT. Its default sends the exception's
 * message, which can quote the frame or name server internals, so this handler sends a fixed message instead; Spring
 * logs the exception. Frames that {@link StompSecurityInterceptor} rejects use {@link #errorFrame} directly.
 */
@Component
class ProblemDetailStompErrorHandler extends StompSubProtocolErrorHandler {

	private final JsonMapper jsonMapper;

	ProblemDetailStompErrorHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
		StompHeaderAccessor clientFrame = (clientMessage != null)
				? MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class)
				: null;
		return errorFrame(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid STOMP frame."), clientFrame);
	}

	/**
	 * An ERROR frame for the connection that sent {@code clientFrame}. It names the connection's session, so it can be
	 * sent through the client outbound channel.
	 *
	 * @param clientFrame the rejected frame, or {@code null} if it couldn't be parsed
	 */
	Message<byte[]> errorFrame(ProblemDetail problem, StompHeaderAccessor clientFrame) {
		StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
		error.setMessage(problem.getDetail());
		error.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		if (clientFrame != null) {
			error.setSessionId(clientFrame.getSessionId());
			String receipt = clientFrame.getReceipt();
			if (receipt != null) {
				error.setReceiptId(receipt);
			}
		}
		// Spring's STOMP handler reads the frame's headers through a mutable accessor.
		error.setLeaveMutable(true);
		return MessageBuilder.createMessage(jsonMapper.writeValueAsBytes(problem), error.getMessageHeaders());
	}

	/**
	 * Sets the problem's {@code instance} to the STOMP destination the failed frame was sent or subscribed to, the
	 * counterpart of the request path in REST errors. Characters that aren't allowed in a URI are encoded.
	 */
	static ProblemDetail forDestination(ProblemDetail problem, String destination) {
		if (destination != null) {
			problem.setInstance(UriComponentsBuilder.fromPath(destination).build().toUri());
		}
		return problem;
	}

}
