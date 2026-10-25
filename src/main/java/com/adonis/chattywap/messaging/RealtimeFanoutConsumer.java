package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
import com.adonis.chattywap.realtime.MessageBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers the live deliveries of the other instances to this instance's WebSocket clients: consumes this instance's
 * queue, {@value RealtimeFanoutConfig#QUEUE_PREFIX}{@code <instance ID>}, and hands each event to {@code realtime},
 * which delivers it to the event's recipients connected here (ARCHITECTURE.md, decision 26). Transport only.
 * <p>
 * The queue also receives the events this instance published, which {@code realtime} delivered to its clients
 * already; those are skipped, by the {@value RealtimeFanoutPublisher#ORIGIN_HEADER} header. One consumer handles the
 * events in the order they arrived, so the events of one instance are delivered in the order it published them.
 * <p>
 * Never fails: an event it can't read (another type, missing fields, a version it doesn't understand during a rolling
 * deployment) is logged and dropped, and delivery failures are {@code realtime}'s to log. Nothing is retried; the
 * container acknowledges every message.
 * <p>
 * Counted in {@code chattywap.fanout.received}, by {@code outcome}: {@code delivered} (handed to {@code realtime}),
 * {@code own} (skipped) or {@code invalid}.
 */
@Component
class RealtimeFanoutConsumer {

	private static final Logger log = LoggerFactory.getLogger(RealtimeFanoutConsumer.class);

	private final String instanceId;

	private final JsonMapper jsonMapper;

	private final MessageBroadcaster broadcaster;

	private final MeterRegistry meterRegistry;

	RealtimeFanoutConsumer(RealtimeFanoutConfig.Instance instance, JsonMapper jsonMapper,
			MessageBroadcaster broadcaster, MeterRegistry meterRegistry) {
		this.instanceId = instance.id();
		this.jsonMapper = jsonMapper;
		this.broadcaster = broadcaster;
		this.meterRegistry = meterRegistry;
	}

	@RabbitListener(queues = "#{realtimeFanoutQueue.name}", containerFactory = RealtimeFanoutConfig.CONTAINER_FACTORY)
	void onLiveDelivery(Message message) {
		MessageProperties properties = message.getMessageProperties();
		Object origin = properties.getHeader(RealtimeFanoutPublisher.ORIGIN_HEADER);
		if (instanceId.equals(origin)) {
			count("own");
			return;
		}
		EventLogContext.set(message);
		try {
			switch (String.valueOf(properties.getType())) {
				case RealtimeFanoutPublisher.MESSAGE_SENT -> broadcaster.deliverFromOtherInstance(readMessageSent(message));
				case RealtimeFanoutPublisher.RECEIPT_POSITION_CHANGED ->
						broadcaster.deliverFromOtherInstance(readReceiptPositionChanged(message));
				default -> throw new InvalidEventException("Unknown type");
			}
			count("delivered");
		}
		catch (InvalidEventException ex) {
			count("invalid");
			// Only an origin that looks like an ID is logged, so that a header can't forge or break a log line.
			log.warn("Dropped a live delivery from instance {}: {}",
					(origin instanceof String id && LogContext.isValidRequestId(id)) ? id : "unknown", ex.getMessage());
		}
		finally {
			EventLogContext.clear();
		}
	}

	private MessageSentEvent readMessageSent(Message message) {
		MessageSentEvent event = read(message, MessageSentEvent.class);
		MessageResponse sent = event.message();
		if (sent == null || sent.id() == null || sent.conversationId() == null || event.recipientIds() == null) {
			throw new InvalidEventException("The event lacks required fields");
		}
		return event;
	}

	private ReceiptPositionChangedEvent readReceiptPositionChanged(Message message) {
		ReceiptPositionChangedEvent event = read(message, ReceiptPositionChangedEvent.class);
		ReceiptPositionResponse position = event.position();
		if (position == null || position.conversationId() == null || position.userId() == null
				|| event.recipientIds() == null) {
			throw new InvalidEventException("The event lacks required fields");
		}
		return event;
	}

	private <T> T read(Message message, Class<T> type) {
		T event;
		try {
			event = jsonMapper.readValue(message.getBody(), type);
		}
		catch (JacksonException ex) {
			// The parser's message can quote the body, which holds message content.
			throw new InvalidEventException("The body isn't a JSON " + type.getSimpleName());
		}
		if (event == null) {
			throw new InvalidEventException("The body is empty");
		}
		return event;
	}

	private void count(String outcome) {
		meterRegistry.counter("chattywap.fanout.received", "outcome", outcome).increment();
	}

}
