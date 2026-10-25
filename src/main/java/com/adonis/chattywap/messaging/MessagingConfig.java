package com.adonis.chattywap.messaging;

import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * RabbitMQ topology and the settings around it that application.yaml can't express.
 * <ul>
 * <li>{@value #EVENTS_EXCHANGE}: durable topic exchange for all application events. {@link OutboxRelay} publishes
 * to it; routing keys name the event, such as {@value MessageCreatedEvent#ROUTING_KEY}.</li>
 * <li>{@value #MESSAGE_COUNTS_QUEUE}: the example consumer's queue ({@link MessageCountConsumer}), bound to
 * {@value MessageCreatedEvent#ROUTING_KEY}. A quorum queue, so it survives broker restarts and caps redeliveries
 * ({@value #DELIVERY_LIMIT}): a message whose processing keeps crashing the consumer before it can reject it is
 * dead-lettered instead of being redelivered forever.</li>
 * <li>{@value #DEAD_LETTER_EXCHANGE}: direct exchange that receives the messages consumers rejected or the broker
 * gave up on, with the name of their queue as routing key. Each consumer queue has its own dead-letter queue, here
 * {@value #MESSAGE_COUNTS_DEAD_LETTER_QUEUE}, where messages wait for investigation. Nothing consumes it.</li>
 * </ul>
 * Spring declares these when it first connects to the broker, and again after every reconnect. Queue arguments
 * can't change on an existing queue: changing them needs a new queue name, or deleting the queue first.
 * <p>
 * Live deliveries between application instances have an exchange and queues of their own, outside these events
 * ({@link RealtimeFanoutConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class MessagingConfig {

	static final String EVENTS_EXCHANGE = "chattywap.events";

	static final String DEAD_LETTER_EXCHANGE = "chattywap.events.dead-letter";

	static final String MESSAGE_COUNTS_QUEUE = "chattywap.message-counts";

	static final String MESSAGE_COUNTS_DEAD_LETTER_QUEUE = "chattywap.message-counts.dead-letter";

	/** Deliveries of one message after which a quorum queue dead-letters it. */
	static final int DELIVERY_LIMIT = 10;

	/** The relay's own scheduler thread, so that it neither waits for nor delays the WebSocket broker's tasks. */
	static final String OUTBOX_RELAY_SCHEDULER = "outboxRelayScheduler";

	private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

	@Bean
	TopicExchange eventsExchange() {
		return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
	}

	@Bean
	DirectExchange deadLetterExchange() {
		return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
	}

	/**
	 * Dead-lettering is at least once: the queue keeps a rejected message until the dead-letter queue has accepted it,
	 * instead of dropping it if that fails (the default). RabbitMQ requires {@code reject-publish} overflow for that;
	 * the queue has no length limit, so it never rejects a publish.
	 */
	@Bean
	Queue messageCountsQueue() {
		return QueueBuilder.durable(MESSAGE_COUNTS_QUEUE)
				.quorum()
				.deliveryLimit(DELIVERY_LIMIT)
				.deadLetterExchange(DEAD_LETTER_EXCHANGE)
				.deadLetterRoutingKey(MESSAGE_COUNTS_QUEUE)
				.overflow(QueueBuilder.Overflow.rejectPublish)
				.withArgument("x-dead-letter-strategy", "at-least-once")
				.build();
	}

	@Bean
	Queue messageCountsDeadLetterQueue() {
		return QueueBuilder.durable(MESSAGE_COUNTS_DEAD_LETTER_QUEUE).quorum().build();
	}

	@Bean
	Binding messageCountsBinding(Queue messageCountsQueue, TopicExchange eventsExchange) {
		return BindingBuilder.bind(messageCountsQueue).to(eventsExchange).with(MessageCreatedEvent.ROUTING_KEY);
	}

	@Bean
	Binding messageCountsDeadLetterBinding(Queue messageCountsDeadLetterQueue, DirectExchange deadLetterExchange) {
		return BindingBuilder.bind(messageCountsDeadLetterQueue).to(deadLetterExchange).with(MESSAGE_COUNTS_QUEUE);
	}

	/**
	 * Publishing gets a connection of its own. RabbitMQ blocks a connection that publishes when it runs short of
	 * memory or disk; consumers on the same connection couldn't acknowledge then, so the queues couldn't drain and
	 * the alarm couldn't clear.
	 * <p>
	 * The relay learns that the broker returned a message (no queue was bound to its routing key) from the message's
	 * correlation data, and logs it with the event ID. Without a callback, Spring would also log every returned
	 * message as a warning.
	 */
	@Bean
	RabbitTemplateCustomizer outboxPublishingTemplate() {
		return template -> {
			template.setUsePublisherConnection(true);
			template.setReturnsCallback(returned -> log.debug("Broker returned message {}: {} {}",
					returned.getMessage().getMessageProperties().getMessageId(), returned.getReplyCode(),
					returned.getReplyText()));
		};
	}

	/**
	 * Listener retries (configured under {@code spring.rabbitmq.listener.simple.retry}) are for failures that may go
	 * away, such as a database that is briefly unavailable. Failures that would happen again on every attempt go to
	 * the dead-letter queue at once.
	 */
	@Bean
	RabbitListenerRetrySettingsCustomizer listenerRetrySettingsCustomizer() {
		return settings -> settings.setExceptionPredicate(MessagingConfig::isRetryable);
	}

	/**
	 * What happens to a message whose retries are exhausted, or that isn't retried at all: it is logged, counted and
	 * rejected without requeueing, so the broker moves it to its queue's dead-letter queue. The same as Spring's
	 * {@code RejectAndDontRequeueRecoverer}, which would log the message body too.
	 */
	@Bean
	MessageRecoverer deadLetteringMessageRecoverer(MeterRegistry meterRegistry) {
		return (message, cause) -> {
			String queue = message.getMessageProperties().getConsumerQueue();
			EventLogContext.set(message);
			try {
				log.error("Dead-lettering message {} from queue {}: {}", message.getMessageProperties().getMessageId(),
						queue, describe(cause));
			}
			finally {
				EventLogContext.clear();
			}
			meterRegistry.counter("chattywap.events.dead.lettered", "queue", String.valueOf(queue)).increment();
			throw new ListenerExecutionFailedException("Message dead-lettered",
					new AmqpRejectAndDontRequeueException(cause), message);
		};
	}

	@Bean(OUTBOX_RELAY_SCHEDULER)
	ThreadPoolTaskScheduler outboxRelayScheduler(OutboxProperties properties) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("outbox-relay-");
		// On shutdown, let a running batch finish and record its confirmations instead of interrupting it. A batch
		// takes at most the confirm timeout, plus its database statements.
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationMillis(properties.lease().toMillis());
		return scheduler;
	}

	/**
	 * Whether processing a message may succeed if it is tried again. Not for an invalid event, a payload that can't be
	 * read, or data that the database rejects (such as an event about a conversation that doesn't exist): those fail
	 * the same way every time.
	 */
	static boolean isRetryable(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof AmqpRejectAndDontRequeueException || cause instanceof MessageConversionException
					|| cause instanceof DataIntegrityViolationException) {
				return false;
			}
		}
		return true;
	}

	/** The innermost cause's type and message: enough to investigate, without the message body. */
	private static String describe(Throwable failure) {
		Throwable cause = failure;
		while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return (cause == null) ? "unknown failure" : cause.getClass().getName() + ": " + cause.getMessage();
	}

}
