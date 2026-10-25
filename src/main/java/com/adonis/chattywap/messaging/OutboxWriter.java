package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Writes the integration events of other features' domain events to the outbox, inside the transaction that
 * publishes them: the change and its event commit together, or neither does. Nothing here talks to RabbitMQ;
 * {@link OutboxRelay} publishes the rows after the commit.
 * <p>
 * Runs while the publishing service is still in its transaction ({@code @EventListener}, not after the commit), and
 * requires that transaction ({@code MANDATORY}): an event published without one would fail instead of being written
 * on its own. If writing fails, the exception reaches the publishing service and its transaction rolls back.
 */
@Component
class OutboxWriter {

	private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

	private final EntityManager entityManager;

	private final JsonMapper jsonMapper;

	OutboxWriter(EntityManager entityManager, JsonMapper jsonMapper) {
		this.entityManager = entityManager;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * A stored message becomes a {@link MessageCreatedEvent}. Real-time delivery of the message doesn't wait for it:
	 * {@code realtime} delivers {@link MessageSentEvent} itself, after the commit.
	 */
	@EventListener
	@Transactional(propagation = Propagation.MANDATORY)
	void onMessageSent(MessageSentEvent sent) {
		MessageResponse message = sent.message();
		MessageCreatedEvent event = MessageCreatedEvent.of(message.id(), message.conversationId(),
				message.sender().id(), message.createdAt());
		// persist, not a repository's save: the ID is assigned, so save would first look for an existing row. The
		// request's ID, from the log context, follows the event to its consumers' logs.
		entityManager.persist(OutboxEvent.pending(event.eventId(), event.eventType(), event.eventVersion(),
				MessageCreatedEvent.ROUTING_KEY, jsonMapper.writeValueAsString(event), now(),
				MDC.get(LogContext.REQUEST_ID)));
		log.debug("Wrote event {} ({}) for message {} to the outbox", event.eventId(), event.eventType(),
				message.id());
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
