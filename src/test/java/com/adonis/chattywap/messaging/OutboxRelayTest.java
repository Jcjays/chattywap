package com.adonis.chattywap.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the relay makes of each answer from the broker, with the broker and the database mocked. {@code OutboxRelayIT}
 * checks the same against a real broker; these tests pin down each outcome on its own, including a confirmation that
 * never comes.
 */
class OutboxRelayTest {

	private static final Duration CONFIRM_TIMEOUT = Duration.ofMillis(200);

	private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final OutboxRelay relay = new OutboxRelay(repository, rabbitTemplate,
			new OutboxProperties(true, Duration.ofSeconds(1), 4, CONFIRM_TIMEOUT, Duration.ofSeconds(5),
					Duration.ofSeconds(1), Duration.ofMinutes(5)),
			meterRegistry);

	@Test
	void onlyEventsTheBrokerAcknowledgedWithoutReturningThemAreMarkedPublished() {
		OutboxEvent acknowledged = claimedEvent();
		OutboxEvent rejected = claimedEvent();
		OutboxEvent returned = claimedEvent();
		OutboxEvent unanswered = claimedEvent();
		// A full batch, so the run claims again and finds nothing more.
		when(repository.claimDue(anyInt(), anyLong()))
				.thenReturn(List.of(acknowledged, rejected, returned, unanswered), List.of());
		brokerAnswers(Map.of(
				acknowledged.getId(), correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)),
				rejected.getId(), correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "queue full")),
				returned.getId(), correlation -> {
					correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE",
							MessagingConfig.EVENTS_EXCHANGE, "test.unrouted"));
					correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
				},
				unanswered.getId(), correlation -> {
				}));

		assertThat(relay.publishPending()).isEqualTo(1);

		verify(repository).markPublished(List.of(acknowledged.getId()));
		verify(repository).markFailed(eq(rejected.getId()), eq(1), contains("queue full"), eq(1000L));
		verify(repository).markFailed(eq(returned.getId()), eq(1), contains("312 NO_ROUTE"), eq(1000L));
		verify(repository).markFailed(eq(unanswered.getId()), eq(1), contains("No confirmation within"), eq(1000L));
		assertThat(meterRegistry.counter("chattywap.outbox.published").count()).isEqualTo(1);
		assertThat(meterRegistry.counter("chattywap.outbox.failed", "reason", "nack").count()).isEqualTo(1);
		assertThat(meterRegistry.counter("chattywap.outbox.failed", "reason", "returned").count()).isEqualTo(1);
		assertThat(meterRegistry.counter("chattywap.outbox.failed", "reason", "timeout").count()).isEqualTo(1);
	}

	@Test
	void anUnreachableBrokerFailsTheRestOfTheBatchWithoutTryingEachEvent() {
		List<OutboxEvent> batch = List.of(claimedEvent(), claimedEvent(), claimedEvent());
		when(repository.claimDue(anyInt(), anyLong())).thenReturn(batch);
		doThrow(new AmqpConnectException(new ConnectException("Connection refused"))).when(rabbitTemplate)
				.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(relay.publishPending()).isZero();

		verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		batch.forEach(event -> verify(repository).markFailed(eq(event.getId()), eq(1), contains("Connection refused"),
				eq(1000L)));
		verify(repository, never()).markPublished(anyCollection());
		assertThat(meterRegistry.counter("chattywap.outbox.failed", "reason", "unreachable").count()).isEqualTo(3);
	}

	@Test
	void anyFailedSendEndsTheBatch() {
		List<OutboxEvent> batch = List.of(claimedEvent(), claimedEvent(), claimedEvent());
		when(repository.claimDue(anyInt(), anyLong())).thenReturn(batch);
		// A channel that doesn't answer, for example: the next sends would each wait as long.
		doThrow(new AmqpIOException(new IOException("channel RPC timed out"))).when(rabbitTemplate)
				.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(relay.publishPending()).isZero();

		verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		batch.forEach(event -> verify(repository).markFailed(eq(event.getId()), eq(1), contains("channel RPC timed out"),
				eq(1000L)));
		assertThat(meterRegistry.counter("chattywap.outbox.failed", "reason", "send-error").count()).isEqualTo(3);
	}

	@Test
	void eventsLeftWhenOnlyTheConfirmTimeoutRemainsOfTheLeaseAreNotSent() {
		// 300 ms of lease, 200 of them for the confirmations: sending must end after 100 ms.
		OutboxRelay shortLeaseRelay = new OutboxRelay(repository, rabbitTemplate,
				new OutboxProperties(true, Duration.ofSeconds(1), 4, CONFIRM_TIMEOUT, Duration.ofMillis(300),
						Duration.ofSeconds(1), Duration.ofMinutes(5)),
				meterRegistry);
		OutboxEvent sent = claimedEvent();
		OutboxEvent late = claimedEvent();
		when(repository.claimDue(anyInt(), anyLong())).thenReturn(List.of(sent, late));
		doAnswer(invocation -> {
			// A slow send, which uses up the time for sending.
			Thread.sleep(150);
			invocation.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(shortLeaseRelay.publishPending()).isEqualTo(1);

		verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		verify(repository).markPublished(List.of(sent.getId()));
		verify(repository).markFailed(eq(late.getId()), eq(1), contains("no time left"), eq(1000L));
	}

	@Test
	void aRunContinuesWithTheNextBatchWhileBatchesAreFull() {
		List<OutboxEvent> full = List.of(claimedEvent(), claimedEvent(), claimedEvent(), claimedEvent());
		List<OutboxEvent> last = List.of(claimedEvent());
		when(repository.claimDue(anyInt(), anyLong())).thenReturn(full, last);
		doAnswer(invocation -> {
			invocation.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThat(relay.publishPending()).isEqualTo(5);

		verify(repository, times(2)).claimDue(4, 5000L);
	}

	@Test
	void noBatchIsClaimedOnceTheApplicationIsShuttingDown() {
		relay.stop();

		assertThat(relay.publishPending()).isZero();

		verify(repository, never()).claimDue(anyInt(), anyLong());
	}

	@Test
	void theRetryDelayDoublesWithEveryFailedAttemptUpToTheMaximum() {
		Duration initial = Duration.ofSeconds(1);
		Duration max = Duration.ofMinutes(5);

		assertThat(OutboxRelay.retryDelay(1, initial, max)).isEqualTo(Duration.ofSeconds(1));
		assertThat(OutboxRelay.retryDelay(2, initial, max)).isEqualTo(Duration.ofSeconds(2));
		assertThat(OutboxRelay.retryDelay(3, initial, max)).isEqualTo(Duration.ofSeconds(4));
		assertThat(OutboxRelay.retryDelay(9, initial, max)).isEqualTo(Duration.ofSeconds(256));
		assertThat(OutboxRelay.retryDelay(10, initial, max)).isEqualTo(max);
		// Events are retried indefinitely; the delay never overflows.
		assertThat(OutboxRelay.retryDelay(Integer.MAX_VALUE, initial, max)).isEqualTo(max);
	}

	/** An event as a claim returns it: its first attempt counted. */
	private static OutboxEvent claimedEvent() {
		OutboxEvent event = OutboxEvent.pending(UUID.randomUUID(), "TestEvent", 1, "test.event", "{}", Instant.now());
		ReflectionTestUtils.setField(event, "attempts", 1);
		return event;
	}

	/** Answers each send like the broker would answer that event, identified by its message ID. */
	private void brokerAnswers(Map<UUID, Consumer<CorrelationData>> answers) {
		doAnswer(invocation -> {
			MessageProperties properties = invocation.<Message>getArgument(2).getMessageProperties();
			answers.get(UUID.fromString(properties.getMessageId())).accept(invocation.getArgument(3));
			return null;
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}

}
