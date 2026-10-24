package com.adonis.chattywap.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Limits who receives a broadcast. The broker sends a message for a destination to every subscription to it; a
 * message with {@link #RECIPIENT_SESSIONS_HEADER} reaches only the STOMP sessions the header lists and is dropped for
 * all others. Messages without the header, such as errors on a connection's own queue, pass unchanged.
 * <p>
 * Runs on the client outbound channel, once for every session a message is sent to. The header is an internal
 * message header: STOMP frames contain only native headers, so clients never see it.
 */
@Component
class RecipientSessionsInterceptor implements ChannelInterceptor {

	/** The IDs of the STOMP sessions that may receive the message, as a {@code Collection<String>}. */
	static final String RECIPIENT_SESSIONS_HEADER = "chattywapRecipientSessions";

	/**
	 * @return the message, or {@code null} to drop it for this session
	 */
	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		if (message.getHeaders().get(RECIPIENT_SESSIONS_HEADER) instanceof Collection<?> sessionIds
				&& !sessionIds.contains(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))) {
			return null;
		}
		return message;
	}

}
