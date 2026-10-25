package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ThreadChannelConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passes the live deliveries of this instance on to the others: every {@link MessageSentEvent} and
 * {@link ReceiptPositionChangedEvent}, after its commit, to {@value RealtimeFanoutConfig#EXCHANGE}. {@code realtime}
 * delivers them to this instance's clients itself; {@link RealtimeFanoutConsumer} on every other instance delivers
 * them to its own (ARCHITECTURE.md, decision 26).
 * <p>
 * Nothing here holds up the request that committed: the event is handed to one thread of its own, which publishes the
 * events one after the other, on one channel, which it keeps. RabbitMQ keeps the messages of one channel in order, so
 * every instance receives this instance's events in the order they were committed, and one connection's messages
 * reach every other connection in the order they were sent (decision 14). At most {@value #BUFFER_SIZE} events wait
 * for that thread; more are dropped.
 * <p>
 * Best effort and at most once, like the delivery itself: the messages are transient and unconfirmed, and an event
 * that can't be published is dropped, not retried. After a failure, such as RabbitMQ being unreachable, the thread
 * drops events without trying until {@link #PAUSE_AFTER_FAILURE} has passed, so that an outage costs one connection
 * attempt per pause, not one per event. Clients on other instances then don't receive those events live; they read
 * them from the history and the receipts endpoint.
 * <p>
 * The message body is the event as JSON. Its AMQP type is {@value #MESSAGE_SENT} or {@value #RECEIPT_POSITION_CHANGED},
 * the {@value #ORIGIN_HEADER} header names the instance that published it, so that it can skip its own, and the
 * correlation ID is the ID of the request that stored it, for the other instances' logs.
 * <p>
 * Publishes on a connection of its own, to the brokers the application is configured with, so that a broker that
 * blocks it blocks neither the outbox relay nor the consumers. Counted in {@code chattywap.fanout.published} and
 * {@code chattywap.fanout.dropped}, by {@code reason}: {@code buffer-full}, {@code unavailable} (publishing failed,
 * or was paused after a failure) or {@code error} (the event couldn't be serialized, a bug);
 * {@code chattywap.fanout.buffered} is how many are waiting.
 */
@Component
class RealtimeFanoutPublisher implements DisposableBean {

	static final String MESSAGE_SENT = "MessageSent";

	static final String RECEIPT_POSITION_CHANGED = "ReceiptPositionChanged";

	/** The header with the ID of the instance that published the event. */
	static final String ORIGIN_HEADER = "chattywap-origin";

	/** Events that may wait for the publishing thread. */
	static final int BUFFER_SIZE = 10_000;

	/** How long the publishing thread drops events without trying, after it failed to publish one. */
	static final Duration PAUSE_AFTER_FAILURE = Duration.ofSeconds(1);

	/** How long stopping the application waits for events that are still to be published. */
	private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

	private static final Logger log = LoggerFactory.getLogger(RealtimeFanoutPublisher.class);

	private final String instanceId;

	private final JsonMapper jsonMapper;

	private final MeterRegistry meterRegistry;

	private final ThreadChannelConnectionFactory connectionFactory;

	private final RabbitTemplate rabbitTemplate;

	private final ThreadPoolExecutor publishingThread;

	private final Counter published;

	/** Whether a full buffer has been logged since the last event was published: logged once per episode. */
	private final AtomicBoolean bufferFullLogged = new AtomicBoolean();

	// Used by the publishing thread only.

	/** Whether the last attempt to publish failed. */
	private boolean failing;

	private long pausedUntilNanos;

	private long droppedWhileFailing;

	/**
	 * @param connectionFactory the application's connection factory, whose broker settings the publisher's own
	 *                          connection uses
	 * @param connectionDetails the brokers to connect to; all of them, where the connection factory's settings name
	 *                          only the first
	 */
	RealtimeFanoutPublisher(RealtimeFanoutConfig.Instance instance, CachingConnectionFactory connectionFactory,
			RabbitConnectionDetails connectionDetails, JsonMapper jsonMapper, MeterRegistry meterRegistry) {
		this.instanceId = instance.id();
		this.jsonMapper = jsonMapper;
		this.meterRegistry = meterRegistry;
		// Channels bound to the thread that uses them: the publishing thread always publishes on the same one.
		this.connectionFactory = new ThreadChannelConnectionFactory(connectionFactory.getRabbitConnectionFactory());
		this.connectionFactory.setAddresses(connectionDetails.getAddresses().stream()
				.map(address -> address.host() + ":" + address.port())
				.toList());
		this.connectionFactory.setConnectionNameStrategy(factory -> "chattywap-realtime-fanout");
		this.rabbitTemplate = new RabbitTemplate(this.connectionFactory);
		// The factory's own connection, not the separate publisher connection it would create for this template.
		this.rabbitTemplate.setUsePublisherConnection(false);
		CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("realtime-fanout-");
		threadFactory.setDaemon(true);
		this.publishingThread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(BUFFER_SIZE), threadFactory);
		this.published = Counter.builder("chattywap.fanout.published")
				.description("Live deliveries published to the other instances")
				.register(meterRegistry);
		Gauge.builder("chattywap.fanout.buffered", publishingThread, executor -> executor.getQueue().size())
				.description("Live deliveries waiting to be published to the other instances")
				.register(meterRegistry);
	}

	@TransactionalEventListener
	void onMessageSent(MessageSentEvent event) {
		submit(MESSAGE_SENT, event);
	}

	@TransactionalEventListener
	void onReceiptPositionChanged(ReceiptPositionChangedEvent event) {
		submit(RECEIPT_POSITION_CHANGED, event);
	}

	/**
	 * Hands the event to the publishing thread. Runs in the thread that committed, after the commit, so it never
	 * throws: an exception would reach the request, whose change is stored.
	 */
	private void submit(String type, Object event) {
		String requestId = MDC.get(LogContext.REQUEST_ID);
		try {
			publishingThread.execute(() -> publish(type, event, requestId));
		}
		catch (RejectedExecutionException ex) {
			if (publishingThread.isShutdown()) {
				log.debug("Not passing a live delivery on to the other instances: the application is stopping");
				return;
			}
			dropped("buffer-full");
			if (bufferFullLogged.compareAndSet(false, true)) {
				log.warn("{} live deliveries are waiting to be published to the other instances; dropping more until "
						+ "they are published", BUFFER_SIZE);
			}
		}
	}

	/** On the publishing thread. */
	private void publish(String type, Object event, String requestId) {
		if (failing && System.nanoTime() - pausedUntilNanos < 0) {
			droppedWhileFailing++;
			dropped("unavailable");
			return;
		}
		try {
			rabbitTemplate.send(RealtimeFanoutConfig.EXCHANGE, "", toMessage(type, event, requestId));
		}
		catch (AmqpException ex) {
			droppedWhileFailing++;
			dropped("unavailable");
			pausedUntilNanos = System.nanoTime() + PAUSE_AFTER_FAILURE.toNanos();
			if (!failing) {
				failing = true;
				log.warn("Publishing live deliveries to the other instances failed; their clients won't receive them "
						+ "live until it works again: {}", ex.toString());
			}
			return;
		}
		catch (RuntimeException ex) {
			// Not RabbitMQ: the event couldn't be serialized. A bug, but only this event is lost.
			dropped("error");
			log.error("Publishing a live delivery ({}) to the other instances failed", type, ex);
			return;
		}
		published.increment();
		bufferFullLogged.set(false);
		if (failing) {
			failing = false;
			log.info("Publishing live deliveries to the other instances works again; {} were dropped meanwhile",
					droppedWhileFailing);
			droppedWhileFailing = 0;
		}
	}

	private Message toMessage(String type, Object event, String requestId) {
		MessageProperties properties = new MessageProperties();
		properties.setType(type);
		properties.setHeader(ORIGIN_HEADER, instanceId);
		properties.setCorrelationId(requestId);
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setContentEncoding(StandardCharsets.UTF_8.name());
		properties.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
		return new Message(jsonMapper.writeValueAsBytes(event), properties);
	}

	private void dropped(String reason) {
		meterRegistry.counter("chattywap.fanout.dropped", "reason", reason).increment();
	}

	/**
	 * Gives the events still waiting a moment to be published, then closes the connection. Runs after the web server
	 * has stopped, so no more events come.
	 */
	@Override
	public void destroy() throws InterruptedException {
		publishingThread.shutdown();
		try {
			if (!publishingThread.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				log.info("Stopping with {} live deliveries not published to the other instances",
						publishingThread.shutdownNow().size());
			}
		}
		finally {
			connectionFactory.destroy();
		}
	}

}
