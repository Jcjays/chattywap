package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.CannotAcquireLockException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How the consumer reads and validates events before anything is processed. {@code MessageCountConsumerIT} covers
 * acknowledgements, retries and dead-lettering against a real broker.
 */
class MessageCountConsumerTest {

	private static final String VALID = """
			{"eventId":"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f","eventType":"MessageCreated","eventVersion":1,
			"occurredAt":"2026-10-24T10:30:00.123456Z","messageId":"1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
			"conversationId":"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d","senderId":"5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11"}
			""";

	private final MessageCountService service = mock(MessageCountService.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final MessageCountConsumer consumer = new MessageCountConsumer(JsonMapper.builder().build(), service,
			meterRegistry);

	@Test
	void aValidEventIsHandedToTheService() {
		when(service.recordMessageCreated(any())).thenReturn(true);

		consumer.onMessageCreated(message(VALID));

		verify(service).recordMessageCreated(new MessageCreatedEvent(
				UUID.fromString("0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f"), "MessageCreated", 1,
				Instant.parse("2026-10-24T10:30:00.123456Z"), UUID.fromString("1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"),
				UUID.fromString("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"),
				UUID.fromString("5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11")));
		assertThat(meterRegistry.counter("chattywap.events.consumed", "consumer", "message-counts", "outcome",
				"processed").count()).isEqualTo(1);
	}

	@Test
	void fieldsAddedInACompatibleChangeAreIgnored() {
		when(service.recordMessageCreated(any())).thenReturn(true);

		consumer.onMessageCreated(message(VALID.replace("{", "{\"addedLater\":\"value\",")));

		verify(service).recordMessageCreated(any());
	}

	@Test
	void anEventProcessedBeforeCountsAsADuplicate() {
		when(service.recordMessageCreated(any())).thenReturn(false);

		consumer.onMessageCreated(message(VALID));

		assertThat(meterRegistry.counter("chattywap.events.consumed", "consumer", "message-counts", "outcome",
				"duplicate").count()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"not JSON",
			"null",
			"[]",
			"{}",
			"{\"eventId\":\"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f\",\"eventType\":\"MessageCreated\",\"eventVersion\":1}",
			"{\"eventId\":\"not-a-uuid\",\"eventType\":\"MessageCreated\",\"eventVersion\":1}",
			"{\"eventId\":\"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f\",\"eventType\":\"MessageCreated\",\"eventVersion\":\"one\"}"
	})
	void unreadableOrIncompleteEventsAreRejectedWithoutProcessing(String body) {
		assertThatThrownBy(() -> consumer.onMessageCreated(message(body))).isInstanceOf(InvalidEventException.class);

		verifyNoInteractions(service);
		assertThat(meterRegistry.counter("chattywap.events.consumer.failures", "consumer", "message-counts").count())
				.isEqualTo(1);
	}

	@Test
	void anotherEventTypeIsRejected() {
		assertThatThrownBy(() -> consumer.onMessageCreated(message(VALID.replace("MessageCreated", "MessageDeleted"))))
				.isInstanceOf(InvalidEventException.class);

		verifyNoInteractions(service);
	}

	@Test
	void anotherVersionIsRejected() {
		assertThatThrownBy(() -> consumer.onMessageCreated(message(VALID.replace("\"eventVersion\":1",
				"\"eventVersion\":2"))))
				.isInstanceOf(InvalidEventException.class);

		verifyNoInteractions(service);
	}

	@Test
	void aFailureToProcessReachesTheContainerSoTheMessageIsRetried() {
		CannotAcquireLockException failure = new CannotAcquireLockException("simulated");
		when(service.recordMessageCreated(any())).thenThrow(failure);

		assertThatThrownBy(() -> consumer.onMessageCreated(message(VALID))).isSameAs(failure);
		assertThat(MessagingConfig.isRetryable(failure)).isTrue();
	}

	@Test
	void theEventAndTheRequestThatWroteItAreInTheLogContextWhileTheEventIsProcessed() {
		Message message = message(VALID);
		message.getMessageProperties().setCorrelationId("request-1");
		Map<String, String> duringProcessing = new HashMap<>();
		when(service.recordMessageCreated(any())).thenAnswer(invocation -> {
			duringProcessing.put("eventId", MDC.get(LogContext.EVENT_ID));
			duringProcessing.put("requestId", MDC.get(LogContext.REQUEST_ID));
			return true;
		});

		consumer.onMessageCreated(message);

		assertThat(duringProcessing).containsEntry("eventId", message.getMessageProperties().getMessageId())
				.containsEntry("requestId", "request-1");
		assertThat(MDC.get(LogContext.EVENT_ID)).isNull();
		assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
	}

	@Test
	void aCorrelationIdThatIsNoIdIsLeftOutOfTheLogContext() {
		Message message = message(VALID);
		message.getMessageProperties().setCorrelationId("forged\nlog line");
		Map<String, String> duringProcessing = new HashMap<>();
		when(service.recordMessageCreated(any())).thenAnswer(invocation -> {
			duringProcessing.put("requestId", MDC.get(LogContext.REQUEST_ID));
			return true;
		});

		consumer.onMessageCreated(message);

		assertThat(duringProcessing).containsEntry("requestId", null);
	}

	private static Message message(String body) {
		MessageProperties properties = new MessageProperties();
		properties.setMessageId(UUID.randomUUID().toString());
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
	}

}
