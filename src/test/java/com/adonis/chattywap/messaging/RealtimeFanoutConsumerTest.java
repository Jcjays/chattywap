package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
import com.adonis.chattywap.realtime.MessageBroadcaster;
import com.adonis.chattywap.user.dto.UserSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What the consumer makes of each live delivery it receives: other instances' events go to {@code realtime}, its own
 * instance's are skipped, and anything it can't read is dropped without failing. {@code MultiInstanceDeliveryIT}
 * covers the way between two instances through a real broker.
 */
class RealtimeFanoutConsumerTest {

	private static final String THIS_INSTANCE = "0e8f1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b";

	private static final String OTHER_INSTANCE = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a";

	private static final UUID CONVERSATION = UUID.fromString("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d");

	private static final UUID ALICE = UUID.fromString("5f0c5e7e-3c1b-4d2a-9f43-2a7d0f1b9c11");

	private static final UUID BOB = UUID.fromString("9b2d7c4e-1f0a-4c8e-b3d5-6a7e8f9a0b1c");

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	private final MessageBroadcaster broadcaster = mock(MessageBroadcaster.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final RealtimeFanoutConsumer consumer = new RealtimeFanoutConsumer(
			new RealtimeFanoutConfig.Instance(THIS_INSTANCE), jsonMapper, broadcaster, meterRegistry);

	@Test
	void anotherInstancesMessageIsHandedToRealtimeAsItWasPublished() {
		MessageSentEvent event = messageSent();

		consumer.onLiveDelivery(delivery(RealtimeFanoutPublisher.MESSAGE_SENT, OTHER_INSTANCE,
				jsonMapper.writeValueAsString(event)));

		verify(broadcaster).deliverFromOtherInstance(event);
		assertThat(received("delivered")).isEqualTo(1);
	}

	@Test
	void anotherInstancesReceiptChangeIsHandedToRealtimeAsItWasPublished() {
		ReceiptPositionChangedEvent event = new ReceiptPositionChangedEvent(new ReceiptPositionResponse(CONVERSATION,
				BOB, 3, Instant.parse("2026-10-24T10:32:00.000789Z"), 2, Instant.parse("2026-10-24T10:31:00.000001Z")),
				Set.of(ALICE, BOB));

		consumer.onLiveDelivery(delivery(RealtimeFanoutPublisher.RECEIPT_POSITION_CHANGED, OTHER_INSTANCE,
				jsonMapper.writeValueAsString(event)));

		verify(broadcaster).deliverFromOtherInstance(event);
		assertThat(received("delivered")).isEqualTo(1);
	}

	@Test
	void thisInstancesOwnEventsAreSkipped() {
		consumer.onLiveDelivery(delivery(RealtimeFanoutPublisher.MESSAGE_SENT, THIS_INSTANCE,
				jsonMapper.writeValueAsString(messageSent())));

		verifyNoInteractions(broadcaster);
		assertThat(received("own")).isEqualTo(1);
	}

	@Test
	void fieldsANewerVersionAddedAreIgnored() {
		String json = jsonMapper.writeValueAsString(messageSent());

		consumer.onLiveDelivery(delivery(RealtimeFanoutPublisher.MESSAGE_SENT, OTHER_INSTANCE,
				json.replace("{\"message\":{", "{\"addedLater\":1,\"message\":{\"alsoAdded\":true,")));

		verify(broadcaster).deliverFromOtherInstance(messageSent());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"not JSON",
			"null",
			"[]",
			"{}",
			"{\"message\":null,\"recipientIds\":[]}",
			"{\"message\":{\"id\":\"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f\"},\"recipientIds\":[]}",
			"{\"message\":{\"id\":\"0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f\",\"conversationId\":\"7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d\"}}",
			"{\"message\":{\"id\":\"not-a-uuid\"},\"recipientIds\":[]}"
	})
	void unreadableOrIncompleteEventsAreDroppedWithoutFailing(String body) {
		consumer.onLiveDelivery(delivery(RealtimeFanoutPublisher.MESSAGE_SENT, OTHER_INSTANCE, body));

		verifyNoInteractions(broadcaster);
		assertThat(received("invalid")).isEqualTo(1);
	}

	@Test
	void anUnknownTypeIsDroppedWithoutFailing() {
		consumer.onLiveDelivery(delivery("MessageDeleted", OTHER_INSTANCE, jsonMapper.writeValueAsString(messageSent())));
		consumer.onLiveDelivery(delivery(null, OTHER_INSTANCE, jsonMapper.writeValueAsString(messageSent())));

		verifyNoInteractions(broadcaster);
		assertThat(received("invalid")).isEqualTo(2);
	}

	@Test
	void theStoringRequestsIdIsInTheLogContextWhileTheEventIsDelivered() {
		List<String> requestIds = new ArrayList<>();
		doAnswer(invocation -> requestIds.add(MDC.get(LogContext.REQUEST_ID))).when(broadcaster)
				.deliverFromOtherInstance(any(MessageSentEvent.class));
		Message delivery = delivery(RealtimeFanoutPublisher.MESSAGE_SENT, OTHER_INSTANCE,
				jsonMapper.writeValueAsString(messageSent()));
		delivery.getMessageProperties().setCorrelationId("c0ffee00-1234-4abc-8def-0123456789ab");

		consumer.onLiveDelivery(delivery);

		assertThat(requestIds).containsExactly("c0ffee00-1234-4abc-8def-0123456789ab");
		assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
	}

	private static MessageSentEvent messageSent() {
		return new MessageSentEvent(new MessageResponse(UUID.fromString("0b9e7d1c-4f3a-4e2b-9c8d-7a6b5c4d3e2f"),
				CONVERSATION, 7, new UserSummary(ALICE, "Alice"), "Who is bringing snacks?",
				Instant.parse("2026-10-24T10:30:00.123456Z")), Set.of(ALICE, BOB));
	}

	private static Message delivery(String type, String origin, String body) {
		MessageProperties properties = new MessageProperties();
		properties.setType(type);
		properties.setHeader(RealtimeFanoutPublisher.ORIGIN_HEADER, origin);
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
	}

	private double received(String outcome) {
		return meterRegistry.counter("chattywap.fanout.received", "outcome", outcome).count();
	}

}
