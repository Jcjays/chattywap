package com.adonis.chattywap.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Outbox relay settings, bound from the {@code chattywap.messaging.outbox.*} properties in application.yaml.
 *
 * @param relayEnabled      whether this instance publishes outbox events. Events are still written to the outbox
 *                          when it is off.
 * @param pollInterval      pause between two runs of the relay
 * @param batchSize         events claimed, published and confirmed together. A run continues with the next batch
 *                          as long as batches are full.
 * @param confirmTimeout    how long to wait for the broker's confirmations of a batch
 * @param lease             how long a claimed event is reserved for the relay that claimed it. Afterwards, any
 *                          relay may claim it again. Longer than {@code confirmTimeout}, so that a relay that is
 *                          still waiting for confirmations doesn't lose its events to another one.
 * @param initialRetryDelay delay before retrying an event after its first failed attempt
 * @param maxRetryDelay     the delay doubles after every failed attempt, up to this
 */
@ConfigurationProperties("chattywap.messaging.outbox")
@Validated
record OutboxProperties(
		boolean relayEnabled,
		@NotNull Duration pollInterval,
		@Min(1) @Max(1000) int batchSize,
		@NotNull Duration confirmTimeout,
		@NotNull Duration lease,
		@NotNull Duration initialRetryDelay,
		@NotNull Duration maxRetryDelay) {

	OutboxProperties {
		if (lease != null && confirmTimeout != null && lease.compareTo(confirmTimeout) <= 0) {
			throw new IllegalArgumentException("chattywap.messaging.outbox.lease must be longer than confirm-timeout");
		}
	}

}
