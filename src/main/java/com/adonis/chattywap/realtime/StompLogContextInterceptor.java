package com.adonis.chattywap.realtime;

import com.adonis.chattywap.common.LogContext;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Gives every STOMP frame a client sends a request ID, like {@code RequestLoggingFilter} gives HTTP requests, and puts
 * it into the log context with the connection's user ID while the frame is checked and handled. A message sent through
 * STOMP thus has the same trail in the logs and the outbox as one sent through REST.
 * <p>
 * Runs first on the client inbound channel, before {@link StompSecurityInterceptor}: the ID is added to the frame as
 * an internal header, so that it is the same in the checks ({@link #preSend}) and in the handler, which may run on
 * another thread ({@link #beforeHandle}). The user is the connection's, once its CONNECT frame was accepted.
 */
@Component
class StompLogContextInterceptor implements ExecutorChannelInterceptor {

	/** The frame's request ID, as an internal message header: clients never see it. */
	static final String REQUEST_ID_HEADER = "chattywapRequestId";

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor frame = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (frame != null && frame.isMutable() && frame.getHeader(REQUEST_ID_HEADER) == null) {
			frame.setHeader(REQUEST_ID_HEADER, LogContext.newRequestId());
		}
		set(message);
		return message;
	}

	@Override
	public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
		clear();
	}

	@Override
	public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
		set(message);
		return message;
	}

	@Override
	public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
			Exception ex) {
		clear();
	}

	private static void set(Message<?> message) {
		if (message.getHeaders().get(REQUEST_ID_HEADER) instanceof String requestId) {
			MDC.put(LogContext.REQUEST_ID, requestId);
		}
		Principal user = SimpMessageHeaderAccessor.getUser(message.getHeaders());
		if (user != null) {
			MDC.put(LogContext.USER_ID, user.getName());
		}
	}

	private static void clear() {
		MDC.remove(LogContext.REQUEST_ID);
		MDC.remove(LogContext.USER_ID);
	}

}
