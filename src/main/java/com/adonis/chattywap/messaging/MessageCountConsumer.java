package com.adonis.chattywap.messaging;

import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The example consumer of {@code MessageCreated} events, on {@value MessagingConfig#MESSAGE_COUNTS_QUEUE}. Transport
 * only: reads and validates the event, then hands it to {@link MessageCountService}.
 * <p>
 * Acknowledgement is the container's ({@code acknowledge-mode: auto}): it acknowledges the message after this method
 * returned, so only once the effect has committed. If the method throws, the listener's retry policy tries again
 * with backoff (configured under {@code spring.rabbitmq.listener.simple.retry}); once the retries are exhausted, or
 * at once for an invalid event, the message is rejected without requeueing and the broker moves it to the
 * dead-letter queue. If the application stops before acknowledging, the broker delivers the message again; the
 * service's deduplication makes that harmless.
 * <p>
 * It doesn't deliver anything to WebSocket clients: {@code realtime} already did when the message was stored.
 */
@Component
class MessageCountConsumer {

	private static final Logger log = LoggerFactory.getLogger(MessageCountConsumer.class);

	private final JsonMapper jsonMapper;

	private final MessageCountService messageCountService;

	private final Counter processed;

	private final Counter duplicates;

	private final Counter failures;

	MessageCountConsumer(JsonMapper jsonMapper, MessageCountService messageCountService,
			MeterRegistry meterRegistry) {
		this.jsonMapper = jsonMapper;
		this.messageCountService = messageCountService;
		this.processed = consumed(meterRegistry, "processed");
		this.duplicates = consumed(meterRegistry, "duplicate");
		this.failures = Counter.builder("chattywap.events.consumer.failures")
				.description("Failed processing attempts, retries included")
				.tag("consumer", MessageCountService.CONSUMER_NAME)
				.register(meterRegistry);
	}

	@RabbitListener(queues = MessagingConfig.MESSAGE_COUNTS_QUEUE)
	void onMessageCreated(Message message) {
		// The relay sets the event ID as the message ID, so even an unreadable body can be traced.
		String messageId = message.getMessageProperties().getMessageId();
		EventLogContext.set(message);
		try {
			MessageCreatedEvent event = read(message);
			if (messageCountService.recordMessageCreated(event)) {
				processed.increment();
				log.debug("Processed event {} for message {}", event.eventId(), event.messageId());
			}
			else {
				duplicates.increment();
				log.debug("Skipped event {}: already processed", event.eventId());
			}
		}
		catch (RuntimeException ex) {
			failures.increment();
			log.warn("Processing message {} failed: {}", messageId, ex.toString());
			throw ex;
		}
		finally {
			EventLogContext.clear();
		}
	}

	/**
	 * The event in the message. Fields it doesn't know are ignored (compatible additions to version
	 * {@value MessageCreatedEvent#EVENT_VERSION}); missing ones, another type or another version make it invalid.
	 */
	private MessageCreatedEvent read(Message message) {
		MessageCreatedEvent event;
		try {
			event = jsonMapper.readValue(message.getBody(), MessageCreatedEvent.class);
		}
		catch (JacksonException ex) {
			// The parser's message can quote the body; the dead-letter queue keeps the body for investigation.
			throw new InvalidEventException("The body isn't a JSON event with fields of the expected types");
		}
		if (event == null || event.eventId() == null || event.occurredAt() == null || event.messageId() == null
				|| event.conversationId() == null || event.senderId() == null) {
			throw new InvalidEventException("The event lacks required fields");
		}
		if (!MessageCreatedEvent.EVENT_TYPE.equals(event.eventType())
				|| event.eventVersion() != MessageCreatedEvent.EVENT_VERSION) {
			throw new InvalidEventException("The event isn't " + MessageCreatedEvent.EVENT_TYPE + " version "
					+ MessageCreatedEvent.EVENT_VERSION);
		}
		return event;
	}

	private static Counter consumed(MeterRegistry meterRegistry, String outcome) {
		return Counter.builder("chattywap.events.consumed")
				.description("Events processed, or skipped because they had been processed before")
				.tag("consumer", MessageCountService.CONSUMER_NAME)
				.tag("outcome", outcome)
				.register(meterRegistry);
	}

}
