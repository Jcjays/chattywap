package com.adonis.chattywap.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.UUID;

/**
 * STOMP over WebSocket, for delivering messages to connected clients as they are sent.
 * <ul>
 * <li>Endpoint: {@value #ENDPOINT}, plain WebSocket without a SockJS fallback. The handshake needs no access token:
 * clients authenticate in the STOMP CONNECT frame ({@link StompSecurityInterceptor}).</li>
 * <li>Client to server: members send a conversation's messages to {@code /app/conversations/{conversationId}/messages}
 * ({@link MessageStompController}).</li>
 * <li>Server to client: a conversation's messages on {@value #MESSAGES_TOPIC} ({@link MessageBroadcaster}), and the
 * errors of the connection's own messages on {@code /user/queue/errors}.</li>
 * </ul>
 * The broker is Spring's simple in-memory broker. It delivers only to clients connected to this application instance
 * (see ARCHITECTURE.md, unresolved decision 1).
 * <p>
 * Frames from one connection are processed one at a time, in the order they arrive, and messages to one connection
 * are sent in the order they were published. Otherwise the server processes frames in parallel, and two messages a
 * client sends right after each other could be stored, and delivered, in the opposite order.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(RealtimeProperties.class)
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	/** The path of the WebSocket endpoint. {@code auth.SecurityConfig} lets its handshake through. */
	static final String ENDPOINT = "/ws";

	/** Destinations with this prefix go to {@code @MessageMapping} methods. */
	static final String APPLICATION_PREFIX = "/app";

	/** Where members send a conversation's messages, after {@link #APPLICATION_PREFIX}. */
	static final String SEND_MESSAGE = "/conversations/{conversationId}/messages";

	/** Where members receive a conversation's messages. */
	static final String MESSAGES_TOPIC = "/topic/conversations/{conversationId}/messages";

	/** Prefix of user destinations, which the server resolves to the connections of one user. */
	static final String USER_PREFIX = "/user";

	/**
	 * Where a connection receives the errors of the messages it sent, as a user destination: clients subscribe to
	 * {@code /user/queue/errors}.
	 */
	static final String ERRORS_QUEUE = "/queue/errors";

	private final RealtimeProperties properties;

	private final StompSecurityInterceptor securityInterceptor;

	private final RecipientSessionsInterceptor recipientSessionsInterceptor;

	private final ProblemDetailStompErrorHandler errorHandler;

	WebSocketConfig(RealtimeProperties properties, StompSecurityInterceptor securityInterceptor,
			RecipientSessionsInterceptor recipientSessionsInterceptor, ProblemDetailStompErrorHandler errorHandler) {
		this.properties = properties;
		this.securityInterceptor = securityInterceptor;
		this.recipientSessionsInterceptor = recipientSessionsInterceptor;
		this.errorHandler = errorHandler;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint(ENDPOINT)
				// Browsers send the page's origin with the handshake; only these origins, and pages served by this
				// application, may connect. Clients that send no Origin header, such as mobile apps, aren't limited.
				.setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
		registry.setPreserveReceiveOrder(true);
		registry.setErrorHandler(errorHandler);
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.setApplicationDestinationPrefixes(APPLICATION_PREFIX);
		// "/queue" carries the user destinations ("/user/queue/errors" becomes "/queue/errors-user<session>").
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setUserDestinationPrefix(USER_PREFIX);
		registry.setPreservePublishOrder(true);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(securityInterceptor);
	}

	@Override
	public void configureClientOutboundChannel(ChannelRegistration registration) {
		registration.interceptors(recipientSessionsInterceptor);
	}

	/** The destination where members receive the conversation's messages. */
	static String messagesTopic(UUID conversationId) {
		return MESSAGES_TOPIC.replace("{conversationId}", conversationId.toString());
	}

}
