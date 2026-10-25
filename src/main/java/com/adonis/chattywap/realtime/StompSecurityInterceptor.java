package com.adonis.chattywap.realtime;

import com.adonis.chattywap.common.DatabaseUnavailable;
import com.adonis.chattywap.conversation.ConversationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.ErrorResponseException;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Decides which STOMP frames from clients the server accepts. Runs for every frame a client sends, before any handler
 * or the broker sees it. Deny by default:
 * <ul>
 * <li><b>CONNECT</b> must carry the access token as {@code Authorization: Bearer <accessToken>}. It is validated
 * like on every REST request, by Spring Security's {@link JwtAuthenticationProvider} with the application's
 * {@link JwtDecoder} (signature, expiry, issuer, audience), and its user becomes the connection's user: the
 * {@code Principal} of every later frame. User IDs or logins sent by the client are never used.</li>
 * <li><b>SUBSCRIBE</b> is allowed to a conversation's messages topic and receipts topic for its members, as decided by
 * {@link ConversationService#requireMembership}, and to the connection's own error queue. Nothing else.</li>
 * <li><b>SEND</b> is allowed to the destination where members post a conversation's messages. Whether the user may
 * post to that conversation is decided by {@code MessageService}, like for REST. Sending to a broker destination,
 * such as a conversation's topic, would bypass storing the message, and is rejected.</li>
 * <li>SUBSCRIBE and SEND also need the connection's access token to still be valid, the same rule as for every REST
 * request. A connection whose token has expired can't subscribe or send any more; the client connects again with a
 * new token.</li>
 * <li>UNSUBSCRIBE, DISCONNECT and heartbeats are always allowed. Every other frame is rejected.</li>
 * </ul>
 * A rejected frame never reaches a handler or the broker. The client gets an ERROR frame with a problem detail
 * ({@link ProblemDetailStompErrorHandler}), and the server closes the connection.
 * <p>
 * Conversation IDs in destinations must be written as the API returns them (lower case, with hyphens). The broker
 * matches destinations as strings, so a subscription with another spelling of the ID would never receive anything.
 * <p>
 * The ERROR frame is sent from here instead of by throwing an exception. Frames from one connection are processed
 * in order ({@link WebSocketConfig}), and the channel decorator that does this only logs exceptions from
 * interceptors, without telling the client; the log entry would also contain the frame's headers, the token
 * included. For the same reason, unexpected exceptions are caught here too. Because the decorator reports every frame
 * as accepted, Spring still publishes a {@code SessionSubscribeEvent} for a rejected SUBSCRIBE: nothing may rely on
 * those events, or on the subscriptions {@code SimpUserRegistry} derives from them, to mean the broker accepted it.
 */
@Component
class StompSecurityInterceptor implements ChannelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(StompSecurityInterceptor.class);

	/** STOMP header names are case-sensitive. */
	private static final String AUTHORIZATION_HEADER = "Authorization";

	private static final String BEARER_PREFIX = "Bearer ";

	private static final String SEND_DESTINATION = WebSocketConfig.APPLICATION_PREFIX + WebSocketConfig.SEND_MESSAGE;

	private static final String ERRORS_SUBSCRIPTION = WebSocketConfig.USER_PREFIX + WebSocketConfig.ERRORS_QUEUE;

	/** The form of {@link UUID#toString()}, which is how the API writes IDs. */
	private static final Pattern CANONICAL_UUID =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	/** Matches destinations like {@code @MessageMapping} does. */
	private static final PathMatcher DESTINATIONS = new AntPathMatcher();

	private final JwtDecoder jwtDecoder;

	private final AuthenticationProvider authenticationProvider;

	private final ConversationService conversationService;

	private final ProblemDetailStompErrorHandler errorHandler;

	private final MessageChannel clientOutboundChannel;

	private final MeterRegistry meterRegistry;

	/**
	 * @param clientOutboundChannel where ERROR frames go. It is created by the broker configuration, which needs this
	 *                              interceptor first, so it is looked up when the first frame is rejected.
	 */
	StompSecurityInterceptor(JwtDecoder jwtDecoder, ConversationService conversationService,
			ProblemDetailStompErrorHandler errorHandler,
			@Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
			MeterRegistry meterRegistry) {
		this.jwtDecoder = jwtDecoder;
		this.authenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
		this.conversationService = conversationService;
		this.errorHandler = errorHandler;
		this.clientOutboundChannel = clientOutboundChannel;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * @return the frame to pass on, or {@code null} if it is rejected
	 */
	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor frame = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (frame == null) {
			// Spring's STOMP handler passes every client frame with these headers. Without them, nothing can be checked.
			log.error("Dropped a message without STOMP headers on the client inbound channel");
			return null;
		}
		try {
			check(frame);
			return message;
		}
		catch (ErrorResponseException ex) {
			return reject(frame, ex.getBody());
		}
		catch (RuntimeException ex) {
			if (DatabaseUnavailable.isCause(ex)) {
				log.warn("Database unavailable while checking a STOMP {} frame: {}", frame.getCommand(),
						DatabaseUnavailable.describe(ex));
				return reject(frame, DatabaseUnavailable.problem());
			}
			log.error("Unexpected error while checking a STOMP {} frame", frame.getCommand(), ex);
			return reject(frame, ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
					"An unexpected error occurred."));
		}
	}

	/**
	 * @throws ErrorResponseException if the frame is rejected; its problem detail goes into the ERROR frame
	 */
	private void check(StompHeaderAccessor frame) {
		StompCommand command = frame.getCommand();
		if (command == null) {
			// A heartbeat.
			return;
		}
		switch (command) {
			case CONNECT, STOMP -> authenticate(frame);
			case SUBSCRIBE -> authorizeSubscription(frame);
			case SEND -> authorizeSend(frame);
			case UNSUBSCRIBE, DISCONNECT -> {
				// Always allowed: they only end what the connection itself started.
			}
			default -> throw rejection(HttpStatus.BAD_REQUEST, "Unsupported STOMP frame.");
		}
	}

	private void authenticate(StompHeaderAccessor frame) {
		String authorization = frame.getFirstNativeHeader(AUTHORIZATION_HEADER);
		// Only this check needs the token. Removing it keeps it out of everything the frame is passed on to.
		frame.removeNativeHeader(AUTHORIZATION_HEADER);
		if (authorization == null
				|| !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			throw unauthenticated();
		}
		String token = authorization.substring(BEARER_PREFIX.length()).strip();
		if (token.isEmpty()) {
			throw unauthenticated();
		}
		try {
			frame.setUser(authenticationProvider.authenticate(new BearerTokenAuthenticationToken(token)));
		}
		catch (AuthenticationException ex) {
			throw unauthenticated();
		}
	}

	private void authorizeSubscription(StompHeaderAccessor frame) {
		UUID userId = requireAuthenticated(frame);
		String destination = frame.getDestination();
		if (ERRORS_SUBSCRIPTION.equals(destination)) {
			return;
		}
		String topic = (destination != null && DESTINATIONS.match(WebSocketConfig.RECEIPTS_TOPIC, destination))
				? WebSocketConfig.RECEIPTS_TOPIC
				: WebSocketConfig.MESSAGES_TOPIC;
		conversationService.requireMembership(userId, conversationId(topic, destination));
	}

	private void authorizeSend(StompHeaderAccessor frame) {
		requireAuthenticated(frame);
		conversationId(SEND_DESTINATION, frame.getDestination());
	}

	/**
	 * The connection's user, from its CONNECT frame. Its access token is validated again, so that it must still be
	 * valid, as on every REST request.
	 */
	private UUID requireAuthenticated(StompHeaderAccessor frame) {
		if (!(frame.getUser() instanceof JwtAuthenticationToken user)) {
			throw unauthenticated();
		}
		try {
			jwtDecoder.decode(user.getToken().getTokenValue());
		}
		catch (JwtException ex) {
			throw unauthenticated();
		}
		return UUID.fromString(user.getName());
	}

	/**
	 * The conversation ID in {@code destination}, which must match {@code template}.
	 */
	private static UUID conversationId(String template, String destination) {
		if (destination != null && DESTINATIONS.match(template, destination)) {
			String id = DESTINATIONS.extractUriTemplateVariables(template, destination).get("conversationId");
			if (id != null && CANONICAL_UUID.matcher(id).matches()) {
				return UUID.fromString(id);
			}
		}
		throw rejection(HttpStatus.NOT_FOUND, "Unknown destination.");
	}

	/**
	 * Sends the client an ERROR frame, after which the server closes the connection, and drops the frame. Counted in
	 * {@code chattywap.stomp.frames.rejected}, by command and status: failed authentications are {@code CONNECT} with
	 * 401, refused subscriptions {@code SUBSCRIBE} with 404.
	 */
	private Message<?> reject(StompHeaderAccessor frame, ProblemDetail problem) {
		String command = (frame.getCommand() != null) ? frame.getCommand().name() : "NONE";
		meterRegistry.counter("chattywap.stomp.frames.rejected", "command", command, "status",
				String.valueOf(problem.getStatus())).increment();
		log.debug("Rejected a STOMP {} frame: {} {}", command, problem.getStatus(), problem.getDetail());
		ProblemDetailStompErrorHandler.forDestination(problem, frame.getDestination());
		clientOutboundChannel.send(errorHandler.errorFrame(problem, frame));
		return null;
	}

	/** The same message as for REST requests without a valid access token. */
	private static ErrorResponseException unauthenticated() {
		return rejection(HttpStatus.UNAUTHORIZED, "Missing, invalid or expired access token.");
	}

	private static ErrorResponseException rejection(HttpStatus status, String detail) {
		return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
	}

}
