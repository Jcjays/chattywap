package com.adonis.chattywap.realtime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.DelegatingWebSocketMessageBrokerConfiguration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.util.UUID;

/**
 * STOMP over WebSocket, for delivering messages to connected clients as they are sent.
 * <ul>
 * <li>Endpoint: {@value #ENDPOINT}, plain WebSocket without a SockJS fallback. The handshake needs no access token:
 * clients authenticate in the STOMP CONNECT frame ({@link StompSecurityInterceptor}).</li>
 * <li>Client to server: members send a conversation's messages to {@code /app/conversations/{conversationId}/messages}
 * ({@link MessageStompController}).</li>
 * <li>Server to client: a conversation's messages on {@value #MESSAGES_TOPIC} and the changes of its receipts on
 * {@value #RECEIPTS_TOPIC} ({@link MessageBroadcaster}), and the errors of the connection's own messages on
 * {@code /user/queue/errors}.</li>
 * </ul>
 * The broker is Spring's simple in-memory broker. It delivers only to clients connected to this application instance;
 * {@code messaging} brings it the messages and receipt changes stored on other instances, which
 * {@link MessageBroadcaster} delivers like this instance's own (ARCHITECTURE.md, decision 26).
 * <p>
 * Frames from one connection are processed one at a time, in the order they arrive, on the thread that read them
 * ({@link #configureClientInboundChannel}), and messages to one connection are sent in the order they were published.
 * Otherwise the server processes frames in parallel, and two messages a client sends right after each other could be
 * stored, and delivered, in the opposite order.
 * <p>
 * The broker offers heartbeats ({@link RealtimeProperties#heartbeat()}), so that connections whose client is gone are
 * closed.
 * <p>
 * A connection is closed only after the frames already sent to it went out ({@link BrokerConfig}), so that a client
 * whose frame is rejected receives the ERROR frame before the connection closes.
 */
@Configuration(proxyBeanMethods = false)
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

	/** Where members receive the changes of a conversation's delivery and read receipts. */
	static final String RECEIPTS_TOPIC = "/topic/conversations/{conversationId}/receipts";

	/** Prefix of user destinations, which the server resolves to the connections of one user. */
	static final String USER_PREFIX = "/user";

	/**
	 * Where a connection receives the errors of the messages it sent, as a user destination: clients subscribe to
	 * {@code /user/queue/errors}.
	 */
	static final String ERRORS_QUEUE = "/queue/errors";

	private final RealtimeProperties properties;

	private final StompLogContextInterceptor logContextInterceptor;

	private final StompSecurityInterceptor securityInterceptor;

	private final RecipientSessionsInterceptor recipientSessionsInterceptor;

	private final ProblemDetailStompErrorHandler errorHandler;

	private final TaskScheduler heartbeatScheduler;

	/**
	 * @param heartbeatScheduler the broker's own scheduler, which the broker configuration creates after this class, so
	 *                           it is looked up when the broker starts
	 */
	WebSocketConfig(RealtimeProperties properties, StompLogContextInterceptor logContextInterceptor,
			StompSecurityInterceptor securityInterceptor, RecipientSessionsInterceptor recipientSessionsInterceptor,
			ProblemDetailStompErrorHandler errorHandler,
			@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
		this.properties = properties;
		this.logContextInterceptor = logContextInterceptor;
		this.securityInterceptor = securityInterceptor;
		this.recipientSessionsInterceptor = recipientSessionsInterceptor;
		this.errorHandler = errorHandler;
		this.heartbeatScheduler = heartbeatScheduler;
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
		// Heartbeats only for connections that ask for them; see RealtimeProperties#heartbeat.
		long heartbeat = properties.heartbeat().toMillis();
		registry.enableSimpleBroker("/topic", "/queue")
				.setHeartbeatValue(new long[] {heartbeat, heartbeat})
				.setTaskScheduler(heartbeatScheduler);
		registry.setUserDestinationPrefix(USER_PREFIX);
		registry.setPreservePublishOrder(true);
	}

	/**
	 * Frames are handled in the thread that read them from the connection, one at a time, instead of being queued for
	 * a separate thread pool. The server then reads a connection's next frame only once the previous one is handled,
	 * so a client that sends faster than its frames can be handled is slowed down by TCP, like a client sending REST
	 * requests, instead of filling the server's memory: Spring's per-connection queue for ordered frames has no limit.
	 * Measured before: 20,000 SEND frames of 4,000 characters were read in a second and then waited in memory for
	 * minutes (loadtest/README.md). A connection's frames stay in order. The cost: a frame being handled occupies one
	 * of the server's request threads, as a REST request does.
	 */
	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(logContextInterceptor, securityInterceptor);
		registration.executor(new SyncTaskExecutor());
	}

	@Override
	public void configureClientOutboundChannel(ChannelRegistration registration) {
		registration.interceptors(recipientSessionsInterceptor);
	}

	/** The destination where members receive the conversation's messages. */
	static String messagesTopic(UUID conversationId) {
		return MESSAGES_TOPIC.replace("{conversationId}", conversationId.toString());
	}

	/** The destination where members receive the changes of the conversation's receipts. */
	static String receiptsTopic(UUID conversationId) {
		return RECEIPTS_TOPIC.replace("{conversationId}", conversationId.toString());
	}

	/**
	 * What {@link EnableWebSocketMessageBroker} sets up, configured by {@link WebSocketConfig}, except that every
	 * connection's session is a {@link FlushBeforeCloseSessionDecorator}.
	 */
	@Configuration(proxyBeanMethods = false)
	static class BrokerConfig extends DelegatingWebSocketMessageBrokerConfiguration {

		@Bean
		@Override
		public WebSocketHandler subProtocolWebSocketHandler(AbstractSubscribableChannel clientInboundChannel,
				AbstractSubscribableChannel clientOutboundChannel) {
			SubProtocolWebSocketHandler handler =
					new SubProtocolWebSocketHandler(clientInboundChannel, clientOutboundChannel) {

						@Override
						protected WebSocketSession decorateSession(WebSocketSession session) {
							return new FlushBeforeCloseSessionDecorator(session, getSendTimeLimit(),
									getSendBufferSizeLimit());
						}

					};
			handler.setPhase(getPhase());
			return handler;
		}

	}

}
