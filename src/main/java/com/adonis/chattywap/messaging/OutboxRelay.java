package com.adonis.chattywap.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox events to RabbitMQ, on its own scheduler thread, independent of any request.
 * <p>
 * A run repeats three steps until no event is due:
 * <ol>
 * <li>Claim a batch of due events in one short transaction ({@link OutboxEventRepository#claimDue}). The claim
 * counts the attempt and reserves the events for the lease, so concurrent relays, on this or other instances,
 * claim different events. No lock or transaction is held from here on.</li>
 * <li>Publish them to {@value MessagingConfig#EVENTS_EXCHANGE}, as persistent and mandatory messages, and wait for
 * the broker to confirm them, up to the confirm timeout.</li>
 * <li>Mark the events the broker acknowledged and didn't return as published, in one statement. Every other event
 * stays pending with its error, and is due again after a delay that doubles with every failed attempt, up to the
 * maximum: a negative acknowledgement, a returned (unroutable) message, no confirmation in time, or a broker that
 * can't be reached. Events are retried until they are published; none is dropped.</li>
 * </ol>
 * A send that returns without an exception proves nothing: only the broker's confirmation marks an event published.
 * <p>
 * Delivery is at least once. If the relay stops between the broker's confirmation and step 3, or the database
 * rejects step 3, the event stays pending and is published again once its lease has expired. Consumers deduplicate
 * by event ID. Events aren't ordered: retries, and several relays, publish them in any order.
 */
@Component
class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final int MAX_ERROR_LENGTH = 1000;

	private final OutboxEventRepository repository;

	private final RabbitTemplate rabbitTemplate;

	private final OutboxProperties properties;

	private final MeterRegistry meterRegistry;

	private final Counter published;

	private final Counter retried;

	private volatile boolean stopping;

	OutboxRelay(OutboxEventRepository repository, RabbitTemplate rabbitTemplate, OutboxProperties properties,
			MeterRegistry meterRegistry) {
		this.repository = repository;
		this.rabbitTemplate = rabbitTemplate;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.published = Counter.builder("chattywap.outbox.published")
				.description("Outbox events the broker confirmed")
				.register(meterRegistry);
		this.retried = Counter.builder("chattywap.outbox.retried")
				.description("Publication attempts for events that had been claimed before")
				.register(meterRegistry);
		// Read from the database whenever the metrics are read, never by the relay itself.
		Gauge.builder("chattywap.outbox.pending", repository, OutboxEventRepository::countPending)
				.description("Outbox events not published yet")
				.register(meterRegistry);
		Gauge.builder("chattywap.outbox.oldest.pending.age", repository,
						relayRepository -> ageInSeconds(relayRepository.findOldestPendingCreatedAt()))
				.description("Age of the oldest outbox event not published yet; 0 if there is none")
				.baseUnit("seconds")
				.register(meterRegistry);
	}

	/**
	 * The scheduled run. A failure of the database is logged and the run ends; the next run tries again.
	 */
	@Scheduled(fixedDelayString = "${chattywap.messaging.outbox.poll-interval}",
			scheduler = MessagingConfig.OUTBOX_RELAY_SCHEDULER)
	void run() {
		if (!properties.relayEnabled()) {
			return;
		}
		try {
			publishPending();
		}
		catch (DataAccessException ex) {
			log.warn("Outbox relay run failed, trying again in {}: {}", properties.pollInterval(), ex.getMessage());
		}
	}

	/** Lets a running batch finish, but starts no new one, once the application is shutting down. */
	@EventListener(ContextClosedEvent.class)
	void stop() {
		stopping = true;
	}

	/**
	 * Publishes the events that are due, batch after batch, until a batch isn't full. Also for tests, which run the
	 * relay themselves.
	 *
	 * @return how many events the broker confirmed
	 */
	int publishPending() {
		int confirmed = 0;
		while (!stopping && !Thread.currentThread().isInterrupted()) {
			List<OutboxEvent> batch = repository.claimDue(properties.batchSize(), properties.lease().toMillis());
			if (!batch.isEmpty()) {
				confirmed += publish(batch);
			}
			if (batch.size() < properties.batchSize()) {
				break;
			}
		}
		return confirmed;
	}

	/**
	 * Publishes the batch, and logs its failed attempts once, summed up by reason (see {@link FailedAttempts}).
	 */
	private int publish(List<OutboxEvent> batch) {
		FailedAttempts failures = new FailedAttempts();
		try {
			return publish(batch, failures);
		}
		finally {
			failures.log();
		}
	}

	/**
	 * Sends the batch, then waits for its confirmations. Both together end before the claim's lease does, so that no
	 * other relay claims these events while this one still works on them: sending stops once only the confirm timeout
	 * is left of the lease, and at the first failed send. A send fails because of the connection or the channel
	 * (broker unreachable, not answering, channel closed), and every later send would wait and fail the same way.
	 * Events not sent are failed attempts, retried after their delay.
	 */
	private int publish(List<OutboxEvent> batch, FailedAttempts failures) {
		long sendDeadline = System.nanoTime() + properties.lease().minus(properties.confirmTimeout()).toNanos();
		List<InFlight> inFlight = new ArrayList<>();
		String skipReason = null;
		String skipError = null;
		for (OutboxEvent event : batch) {
			if (event.getAttempts() > 1) {
				retried.increment();
			}
			if (skipReason == null && System.nanoTime() > sendDeadline) {
				skipReason = "out-of-time";
				skipError = "Not sent: no time left before the claim's lease ends";
			}
			if (skipReason != null) {
				recordFailure(event, skipReason, skipError, failures);
				continue;
			}
			CorrelationData correlation = new CorrelationData();
			try {
				rabbitTemplate.send(MessagingConfig.EVENTS_EXCHANGE, event.getRoutingKey(), toMessage(event),
						correlation);
				inFlight.add(new InFlight(event, correlation));
			}
			catch (AmqpException ex) {
				skipReason = (ex instanceof AmqpConnectException) ? "unreachable" : "send-error";
				recordFailure(event, skipReason, "Send failed: " + ex, failures);
				skipError = "Not sent after an earlier send of the batch failed: " + ex;
			}
		}

		List<UUID> confirmed = awaitConfirms(inFlight, failures);
		if (!confirmed.isEmpty()) {
			// If this fails, the events stay pending and are published again after their lease: a duplicate, not a
			// loss.
			repository.markPublished(confirmed);
			published.increment(confirmed.size());
			log.debug("Published {} outbox events: {}", confirmed.size(), confirmed);
		}
		return confirmed.size();
	}

	/**
	 * Waits for the broker's answer to every event sent, all within one confirm timeout. Returns the events it
	 * acknowledged without returning them; records every other outcome as a failure.
	 */
	private List<UUID> awaitConfirms(List<InFlight> inFlight, FailedAttempts failures) {
		long deadline = System.nanoTime() + properties.confirmTimeout().toNanos();
		List<UUID> confirmed = new ArrayList<>();
		for (InFlight sent : inFlight) {
			OutboxEvent event = sent.event();
			try {
				CorrelationData.Confirm confirm = sent.correlation().getFuture()
						.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				// The broker returns an unroutable message before it acknowledges it, so it's known by now.
				ReturnedMessage returned = sent.correlation().getReturned();
				if (!confirm.ack()) {
					recordFailure(event, "nack", "Broker did not accept the event"
							+ ((confirm.reason() != null) ? ": " + confirm.reason() : ""), failures);
				}
				else if (returned != null) {
					recordFailure(event, "returned", "No queue is bound to routing key " + returned.getRoutingKey()
							+ ": " + returned.getReplyCode() + " " + returned.getReplyText(), failures);
				}
				else {
					confirmed.add(event.getId());
				}
			}
			catch (TimeoutException ex) {
				recordFailure(event, "timeout", "No confirmation within " + properties.confirmTimeout(), failures);
			}
			catch (ExecutionException ex) {
				recordFailure(event, "send-error", "Confirmation failed: " + ex.getCause(), failures);
			}
			catch (InterruptedException ex) {
				// Ends the run (a shutdown that doesn't wait). Events still waiting for their confirmation stay claimed
				// and are published again after their lease.
				Thread.currentThread().interrupt();
				break;
			}
		}
		return confirmed;
	}

	/**
	 * Records the failed attempt, with its error, in the event's row; the event is due again after the retry delay.
	 * Logged on its own at {@code DEBUG} only: {@link FailedAttempts} sums up the batch's failures.
	 */
	private void recordFailure(OutboxEvent event, String reason, String error, FailedAttempts failures) {
		Duration retryDelay = retryDelay(event.getAttempts(), properties.initialRetryDelay(),
				properties.maxRetryDelay());
		log.debug("Publishing event {} ({}) failed on attempt {}, next attempt in {}: {}", event.getId(),
				event.getEventType(), event.getAttempts(), retryDelay, error);
		meterRegistry.counter("chattywap.outbox.failed", "reason", reason).increment();
		failures.add(event, reason, error, retryDelay);
		repository.markFailed(event.getId(), event.getAttempts(), truncate(error), retryDelay.toMillis());
	}

	/**
	 * The delay after the {@code attempts}-th failed attempt: {@code initialDelay}, doubled with every further
	 * attempt, never more than {@code maxDelay}.
	 */
	static Duration retryDelay(int attempts, Duration initialDelay, Duration maxDelay) {
		// From 2^30 on, any delay exceeds every sensible maximum; capping the exponent avoids overflowing.
		int doublings = Math.clamp(attempts - 1, 0, 30);
		Duration delay = initialDelay.multipliedBy(1L << doublings);
		return (delay.compareTo(maxDelay) > 0) ? maxDelay : delay;
	}

	/**
	 * The event as it is published: the stored payload as the body, and its ID as the message ID, which
	 * consumers can log before they parse the body. The correlation ID is the request that wrote the event, for the
	 * consumers' logs.
	 */
	private static Message toMessage(OutboxEvent event) {
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setMessageId(event.getId().toString());
		messageProperties.setCorrelationId(event.getCorrelationId());
		messageProperties.setType(event.getEventType());
		messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
		messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		messageProperties.setTimestamp(Date.from(event.getCreatedAt()));
		return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), messageProperties);
	}

	private static String truncate(String error) {
		return (error.length() <= MAX_ERROR_LENGTH) ? error : error.substring(0, MAX_ERROR_LENGTH);
	}

	private static double ageInSeconds(Instant createdAt) {
		return (createdAt == null) ? 0 : Math.max(0, Duration.between(createdAt, Instant.now()).toMillis() / 1000.0);
	}

	private record InFlight(OutboxEvent event, CorrelationData correlation) {
	}

	/**
	 * The failed attempts of one batch, summed up by reason, for one {@code WARN} line per reason instead of one per
	 * event. While RabbitMQ is unreachable every pending event fails on each of its attempts: logged one by one, a
	 * one-minute outage with messages still being sent wrote 4,000 lines (up to 150 a second). The line names the
	 * number of events, the first one with its attempt and error, and the range of their next attempts; each event's
	 * error is also in its row ({@code last_error}).
	 */
	private static final class FailedAttempts {

		private final Map<String, Failures> byReason = new LinkedHashMap<>();

		void add(OutboxEvent event, String reason, String error, Duration retryDelay) {
			Failures failures = byReason.computeIfAbsent(reason, ignored -> new Failures(event, error));
			failures.count++;
			failures.shortestDelay = min(failures.shortestDelay, retryDelay);
			failures.longestDelay = max(failures.longestDelay, retryDelay);
		}

		void log() {
			byReason.forEach((reason, failures) -> OutboxRelay.log.warn(
					"Publishing {} outbox event(s) failed ({}), next attempts in {} to {}; first: event {} ({}) on attempt {}: {}",
					failures.count, reason, failures.shortestDelay, failures.longestDelay, failures.first.getId(),
					failures.first.getEventType(), failures.first.getAttempts(), failures.firstError));
		}

		private static Duration min(Duration a, Duration b) {
			return (a == null || b.compareTo(a) < 0) ? b : a;
		}

		private static Duration max(Duration a, Duration b) {
			return (a == null || b.compareTo(a) > 0) ? b : a;
		}

		private static final class Failures {

			private final OutboxEvent first;

			private final String firstError;

			private int count;

			private Duration shortestDelay;

			private Duration longestDelay;

			Failures(OutboxEvent first, String firstError) {
				this.first = first;
				this.firstError = firstError;
			}

		}

	}

}
