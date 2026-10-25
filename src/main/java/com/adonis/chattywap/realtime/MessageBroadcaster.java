package com.adonis.chattywap.realtime;

import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Delivers every stored message to the conversation's topic, {@link WebSocketConfig#MESSAGES_TOPIC}, for the members
 * connected to this application instance. The sender gets it there too. Changes of a member's delivery and read
 * receipts go to the conversation's {@link WebSocketConfig#RECEIPTS_TOPIC} the same way, the member's own connections
 * included.
 * <p>
 * Events stored on this instance are delivered after the transaction that stored them has committed, in the thread
 * that committed it. A message whose transaction rolled back is never delivered. Messages sent through REST and
 * through STOMP both come from {@code MessageService} as one {@link MessageSentEvent}, so each is delivered once. The
 * payload is the stored message, with its ID and time: the same JSON as the REST response and the history entry.
 * Receipt changes likewise come only from {@code ReceiptService}, after their commit, with the member's position as
 * stored.
 * <p>
 * Events stored on other instances arrive through {@code messaging}, which calls {@link #deliverFromOtherInstance}
 * for each, and passes this instance's events on to the others (ARCHITECTURE.md, decision 26). They are delivered the
 * same way, to the recipients connected here. A connection thus receives each event once, from the instance it is
 * connected to.
 * <p>
 * Only the event's recipients get it, the members when the message was stored. A subscription doesn't end when its
 * user leaves the conversation or is removed from it, and the broker sends a topic's messages to every subscription.
 * So the message names its recipients' sessions, and {@link RecipientSessionsInterceptor} drops it for all others.
 * <p>
 * Delivery is best effort. Clients that aren't connected don't get the message, nor do clients on other instances
 * while RabbitMQ is unavailable, and nothing is retried. The history is the record of what was sent, and the receipts
 * endpoint the record of the positions; clients catch up from them after reconnecting. If delivery fails, the message
 * stays stored and the request that sent it still succeeds: the failure is logged with the message's IDs and counted,
 * never passed on.
 * <p>
 * Every broadcast is counted in {@code chattywap.websocket.broadcasts}, by {@code type} ({@code message},
 * {@code receipt}), {@code origin} ({@code local}: stored on this instance, {@code remote}: on another) and
 * {@code outcome}: {@code sent}, {@code none-connected} (no recipient connected to this instance) or {@code failed}.
 * Messages to slow clients that Spring drops afterwards show in {@code chattywap.websocket.sessions.closed}
 * ({@link WebSocketMetrics}).
 */
@Component
public class MessageBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(MessageBroadcaster.class);

	private static final String LOCAL = "local";

	private static final String REMOTE = "remote";

	private final SimpMessagingTemplate messagingTemplate;

	private final SimpUserRegistry userRegistry;

	private final MeterRegistry meterRegistry;

	MessageBroadcaster(SimpMessagingTemplate messagingTemplate, SimpUserRegistry userRegistry,
			MeterRegistry meterRegistry) {
		this.messagingTemplate = messagingTemplate;
		this.userRegistry = userRegistry;
		this.meterRegistry = meterRegistry;
	}

	@TransactionalEventListener
	void broadcast(MessageSentEvent event) {
		deliver(event, LOCAL);
	}

	@TransactionalEventListener
	void broadcast(ReceiptPositionChangedEvent event) {
		deliver(event, LOCAL);
	}

	/**
	 * Delivers a message that another instance stored to its recipients connected to this one. Never throws: a failure
	 * is logged and counted, as for a message stored here.
	 */
	public void deliverFromOtherInstance(MessageSentEvent event) {
		deliver(event, REMOTE);
	}

	/**
	 * Delivers a receipt change that another instance stored to its recipients connected to this one. Never throws: a
	 * failure is logged and counted, as for a change stored here.
	 */
	public void deliverFromOtherInstance(ReceiptPositionChangedEvent event) {
		deliver(event, REMOTE);
	}

	private void deliver(MessageSentEvent event, String origin) {
		MessageResponse message = event.message();
		try {
			count("message", origin, sendToRecipients(WebSocketConfig.messagesTopic(message.conversationId()), message,
					event.recipientIds()));
		}
		catch (RuntimeException ex) {
			count("message", origin, "failed");
			log.warn("Delivering message {} of conversation {} over WebSocket failed; it is stored: {}", message.id(),
					message.conversationId(), ex.toString());
		}
	}

	private void deliver(ReceiptPositionChangedEvent event, String origin) {
		ReceiptPositionResponse position = event.position();
		try {
			count("receipt", origin, sendToRecipients(WebSocketConfig.receiptsTopic(position.conversationId()),
					position, event.recipientIds()));
		}
		catch (RuntimeException ex) {
			count("receipt", origin, "failed");
			log.warn("Delivering the receipt position of user {} in conversation {} over WebSocket failed; it is stored: {}",
					position.userId(), position.conversationId(), ex.toString());
		}
	}

	/**
	 * @return the outcome, for the metric
	 */
	private String sendToRecipients(String destination, Object payload, Set<UUID> recipientIds) {
		Set<String> sessionIds = connectedSessions(recipientIds);
		if (sessionIds.isEmpty()) {
			return "none-connected";
		}
		SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		headers.setHeader(RecipientSessionsInterceptor.RECIPIENT_SESSIONS_HEADER, sessionIds);
		// Lets the template add the destination to these headers instead of treating them as STOMP headers.
		headers.setLeaveMutable(true);
		messagingTemplate.convertAndSend(destination, payload, headers.getMessageHeaders());
		return "sent";
	}

	private void count(String type, String origin, String outcome) {
		meterRegistry.counter("chattywap.websocket.broadcasts", "type", type, "origin", origin, "outcome", outcome)
				.increment();
	}

	/**
	 * The STOMP sessions of the users on this instance. A user connected from several devices has several.
	 */
	private Set<String> connectedSessions(Set<UUID> userIds) {
		Set<String> sessionIds = new HashSet<>();
		for (UUID userId : userIds) {
			SimpUser user = userRegistry.getUser(userId.toString());
			if (user != null) {
				user.getSessions().forEach(session -> sessionIds.add(session.getId()));
			}
		}
		return sessionIds;
	}

}
