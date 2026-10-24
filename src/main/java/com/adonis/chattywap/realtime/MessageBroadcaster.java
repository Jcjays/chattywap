package com.adonis.chattywap.realtime;

import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
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
 * Runs after the transaction that stored the message has committed, in the thread that committed it. A message whose
 * transaction rolled back is never delivered. Messages sent through REST and through STOMP both come from
 * {@code MessageService} as one {@link MessageSentEvent}, so each is delivered once. The payload is the stored
 * message, with its ID and time: the same JSON as the REST response and the history entry. Receipt changes likewise
 * come only from {@code ReceiptService}, after their commit, with the member's position as stored.
 * <p>
 * Only the event's recipients get it, the members when the message was stored. A subscription doesn't end when its
 * user leaves the conversation or is removed from it, and the broker sends a topic's messages to every subscription.
 * So the message names its recipients' sessions, and {@link RecipientSessionsInterceptor} drops it for all others.
 * <p>
 * Delivery is best effort. Clients that aren't connected, or are connected to another instance, don't get the
 * message, and nothing is retried. The history is the record of what was sent, and the receipts endpoint the record
 * of the positions; clients catch up from them after reconnecting. If delivery fails, the message stays stored and
 * the request that sent it still succeeds: Spring logs exceptions from listeners that run after the commit and doesn't
 * pass them on.
 */
@Component
class MessageBroadcaster {

	private final SimpMessagingTemplate messagingTemplate;

	private final SimpUserRegistry userRegistry;

	MessageBroadcaster(SimpMessagingTemplate messagingTemplate, SimpUserRegistry userRegistry) {
		this.messagingTemplate = messagingTemplate;
		this.userRegistry = userRegistry;
	}

	@TransactionalEventListener
	void broadcast(MessageSentEvent event) {
		MessageResponse message = event.message();
		sendToRecipients(WebSocketConfig.messagesTopic(message.conversationId()), message, event.recipientIds());
	}

	@TransactionalEventListener
	void broadcast(ReceiptPositionChangedEvent event) {
		ReceiptPositionResponse position = event.position();
		sendToRecipients(WebSocketConfig.receiptsTopic(position.conversationId()), position, event.recipientIds());
	}

	private void sendToRecipients(String destination, Object payload, Set<UUID> recipientIds) {
		Set<String> sessionIds = connectedSessions(recipientIds);
		if (sessionIds.isEmpty()) {
			return;
		}
		SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		headers.setHeader(RecipientSessionsInterceptor.RECIPIENT_SESSIONS_HEADER, sessionIds);
		// Lets the template add the destination to these headers instead of treating them as STOMP headers.
		headers.setLeaveMutable(true);
		messagingTemplate.convertAndSend(destination, payload, headers.getMessageHeaders());
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
