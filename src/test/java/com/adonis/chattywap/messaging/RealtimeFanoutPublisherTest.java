package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.user.dto.UserSummary;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the publisher passes events on to the other instances, with RabbitMQ's client mocked: the message it publishes,
 * the order, and what happens when RabbitMQ fails or can't keep up. Nothing of it may hold up or fail the request
 * that committed. {@code MultiInstanceDeliveryIT} covers the way between two instances through a real broker.
 */
@ExtendWith(OutputCaptureExtension.class)
class RealtimeFanoutPublisherTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INSTANCE = "0e8f1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	private final ConnectionFactory rabbitConnectionFactory = mock(ConnectionFactory.class);

	private final Connection connection = mock(Connection.class);

	private final Channel channel = mock(Channel.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private RealtimeFanoutPublisher publisher;

	@BeforeEach
	void setUp() throws Exception {
		CachingConnectionFactory connectionFactory = mock(CachingConnectionFactory.class);
		when(connectionFactory.getRabbitConnectionFactory()).thenReturn(rabbitConnectionFactory);
		RabbitConnectionDetails connectionDetails = () -> List.of(new RabbitConnectionDetails.Address("rabbit", 5672));
		when(rabbitConnectionFactory.newConnection(nullable(ExecutorService.class), anyList(), anyString()))
				.thenReturn(connection);
		when(connection.isOpen()).thenReturn(true);
		when(connection.createChannel()).thenReturn(channel);
		when(channel.isOpen()).thenReturn(true);
		publisher = new RealtimeFanoutPublisher(new RealtimeFanoutConfig.Instance(INSTANCE), connectionFactory,
				connectionDetails, jsonMapper, meterRegistry);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		publisher.destroy();
	}

	@Test
	void aCommittedMessageIsPublishedToEveryInstanceAsJsonWithItsTypeOriginAndRequestId() throws Exception {
		MessageSentEvent event = messageSent("Who is bringing snacks?");
		MDC.put(LogContext.REQUEST_ID, "c0ffee00-1234-4abc-8def-0123456789ab");
		try {
			publisher.onMessageSent(event);
		}
		finally {
			MDC.remove(LogContext.REQUEST_ID);
		}

		await().atMost(TIMEOUT).until(() -> counter("chattywap.fanout.published") == 1);
		ArgumentCaptor<AMQP.BasicProperties> properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
		ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
		verify(channel).basicPublish(eq(RealtimeFanoutConfig.EXCHANGE), eq(""), eq(false), properties.capture(),
				body.capture());
		assertThat(properties.getValue().getType()).isEqualTo(RealtimeFanoutPublisher.MESSAGE_SENT);
		assertThat(properties.getValue().getHeaders())
				.containsEntry(RealtimeFanoutPublisher.ORIGIN_HEADER, INSTANCE);
		assertThat(properties.getValue().getCorrelationId()).isEqualTo("c0ffee00-1234-4abc-8def-0123456789ab");
		assertThat(properties.getValue().getContentType()).isEqualTo("application/json");
		// Transient: RabbitMQ doesn't write it to disk to survive a restart.
		assertThat(properties.getValue().getDeliveryMode()).isEqualTo(1);
		assertThat(jsonMapper.readValue(body.getValue(), MessageSentEvent.class)).isEqualTo(event);
	}

	@Test
	void eventsArePublishedOneAfterTheOtherInTheOrderTheyWereCommittedOnOneChannel() throws Exception {
		List<String> contents = IntStream.rangeClosed(1, 100).mapToObj(i -> "Message " + i).toList();

		contents.forEach(content -> publisher.onMessageSent(messageSent(content)));

		await().atMost(TIMEOUT).until(() -> counter("chattywap.fanout.published") == contents.size());
		ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
		verify(channel, times(contents.size()))
				.basicPublish(anyString(), anyString(), anyBoolean(), any(), bodies.capture());
		assertThat(bodies.getAllValues())
				.extracting(body -> jsonMapper.readValue(body, MessageSentEvent.class).message().content())
				.containsExactlyElementsOf(contents);
		verify(connection, times(1)).createChannel();
	}

	@Test
	void afterAFailureEventsAreDroppedWithoutTryingUntilThePauseHasPassed(CapturedOutput output) throws Exception {
		when(rabbitConnectionFactory.newConnection(nullable(ExecutorService.class), anyList(), anyString()))
				.thenThrow(new ConnectException("Connection refused"))
				.thenReturn(connection);

		// The first fails; the others come within the pause and are dropped without another connection attempt.
		publisher.onMessageSent(messageSent("First"));
		publisher.onMessageSent(messageSent("Second"));
		publisher.onMessageSent(messageSent("Third"));
		await().atMost(TIMEOUT).until(() -> dropped("unavailable") == 3);
		verify(rabbitConnectionFactory, times(1)).newConnection(nullable(ExecutorService.class), anyList(),
				anyString());
		assertThat(output.getOut()).containsOnlyOnce("Publishing live deliveries to the other instances failed");

		// Once the pause has passed, the next event is published, on a new connection.
		await().atMost(TIMEOUT).until(() -> {
			publisher.onMessageSent(messageSent("Later"));
			return counter("chattywap.fanout.published") >= 1;
		});
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(output.getOut())
				.contains("Publishing live deliveries to the other instances works again"));
	}

	@Test
	void whatDoesNotFitIntoTheBufferIsDroppedWithoutHoldingUpTheCommittingThread(CapturedOutput output)
			throws Exception {
		CountDownLatch publishing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			publishing.countDown();
			release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			return null;
		}).doNothing().when(channel).basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));

		// The first is being published, slowly, as when RabbitMQ blocks publishers; the buffer fills behind it.
		publisher.onMessageSent(messageSent("First"));
		assertThat(publishing.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
		for (int i = 0; i < RealtimeFanoutPublisher.BUFFER_SIZE + 5; i++) {
			publisher.onMessageSent(messageSent("Waiting " + i));
		}

		assertThat(dropped("buffer-full")).isEqualTo(5);
		assertThat(meterRegistry.get("chattywap.fanout.buffered").gauge().value())
				.isEqualTo(RealtimeFanoutPublisher.BUFFER_SIZE);
		assertThat(output.getOut()).containsOnlyOnce("live deliveries are waiting to be published");
		release.countDown();
		await().atMost(TIMEOUT)
				.until(() -> counter("chattywap.fanout.published") == RealtimeFanoutPublisher.BUFFER_SIZE + 1);
	}

	@Test
	void stoppingClosesTheConnectionAndIgnoresLaterEvents() throws Exception {
		publisher.onMessageSent(messageSent("Before"));
		await().atMost(TIMEOUT).until(() -> counter("chattywap.fanout.published") == 1);

		publisher.destroy();
		publisher.onMessageSent(messageSent("After"));

		verify(connection).close(anyInt());
		assertThat(meterRegistry.find("chattywap.fanout.dropped").counters()).isEmpty();
	}

	private static MessageSentEvent messageSent(String content) {
		UUID alice = UUID.fromString("5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11");
		return new MessageSentEvent(new MessageResponse(UUID.randomUUID(),
				UUID.fromString("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"), 1, new UserSummary(alice, "Alice"), content,
				Instant.parse("2026-10-24T10:30:00.123456Z")), Set.of(alice));
	}

	private double counter(String name) {
		return meterRegistry.counter(name).count();
	}

	private double dropped(String reason) {
		return meterRegistry.counter("chattywap.fanout.dropped", "reason", reason).count();
	}

}
