package com.adonis.chattywap.messaging;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import com.adonis.chattywap.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The example consumer against RabbitMQ in a container: events are published straight to the events exchange, the
 * way the relay publishes them, and the consumer's effect is read from PostgreSQL. Failures are provoked with
 * triggers on the consumer's table. Every test waits for the final outcome of what it published (processed or
 * dead-lettered), so nothing is still being processed when the next test starts.
 * <p>
 * The test profile allows 3 attempts per message, a few milliseconds apart.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MessageCountConsumerIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private static final int ATTEMPTS = 3;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private UserService userService;

	@BeforeEach
	void emptyTheDeadLetterQueue() {
		amqpAdmin.purgeQueue(MessagingConfig.MESSAGE_COUNTS_DEAD_LETTER_QUEUE, false);
	}

	@Test
	void anEventIsProcessedOnceAndAcknowledged() {
		UUID group = newGroup();
		MessageCreatedEvent event = eventFor(group);
		double processedBefore = consumed("processed");

		publish(event);

		await().atMost(TIMEOUT).until(() -> processedCount(event.eventId()) == 1);
		assertThat(messageCount(group)).isEqualTo(1);
		assertThat(consumed("processed")).isEqualTo(processedBefore + 1);
		// Acknowledged: nothing is left in the queue, nothing went to the dead-letter queue.
		await().atMost(TIMEOUT).until(() -> queueDepth(MessagingConfig.MESSAGE_COUNTS_QUEUE) == 0);
		assertThat(rabbitTemplate.receive(MessagingConfig.MESSAGE_COUNTS_DEAD_LETTER_QUEUE)).isNull();
	}

	@Test
	void aDuplicateDeliveryDoesNotCountTheMessageAgain() {
		UUID group = newGroup();
		MessageCreatedEvent event = eventFor(group);
		MessageCreatedEvent other = eventFor(group);
		double duplicatesBefore = consumed("duplicate");

		publish(event);
		publish(event);
		publish(other);

		await().atMost(TIMEOUT).until(() -> processedCount(other.eventId()) == 1
				&& consumed("duplicate") == duplicatesBefore + 1);
		// Two events, three deliveries: counted twice.
		assertThat(messageCount(group)).isEqualTo(2);
		assertThat(processedCount(event.eventId())).isEqualTo(1);
	}

	@Test
	void aTransientFailureIsRetriedAndTheEventIsThenProcessed() {
		UUID group = newGroup();
		MessageCreatedEvent event = eventFor(group);
		double failuresBefore = failures();

		whileCountingFails(group, ATTEMPTS - 1, () -> {
			publish(event);
			await().atMost(TIMEOUT).until(() -> processedCount(event.eventId()) == 1);
		});

		assertThat(failures()).isEqualTo(failuresBefore + ATTEMPTS - 1);
		assertThat(messageCount(group)).isEqualTo(1);
		assertThat(rabbitTemplate.receive(MessagingConfig.MESSAGE_COUNTS_DEAD_LETTER_QUEUE)).isNull();
	}

	@Test
	void anEventThatKeepsFailingIsDeadLetteredAfterItsRetriesWithoutAnyEffect() {
		UUID group = newGroup();
		MessageCreatedEvent event = eventFor(group);
		double failuresBefore = failures();
		double deadLetteredBefore = deadLettered();

		Message deadLetter = whileCountingFails(group, Integer.MAX_VALUE, () -> {
			publish(event);
			return rabbitTemplate.receive(MessagingConfig.MESSAGE_COUNTS_DEAD_LETTER_QUEUE, TIMEOUT.toMillis());
		});

		assertThat(deadLetter).isNotNull();
		// Enough to investigate: the event ID, where it came from and why it was dead-lettered, and the event.
		MessageProperties properties = deadLetter.getMessageProperties();
		assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
		assertThat(properties.<String>getHeader("x-first-death-queue")).isEqualTo(MessagingConfig.MESSAGE_COUNTS_QUEUE);
		assertThat(properties.<String>getHeader("x-first-death-reason")).isEqualTo("rejected");
		assertThat(jsonMapper.readValue(deadLetter.getBody(), MessageCreatedEvent.class)).isEqualTo(event);
		// Tried as often as configured, then given up on.
		assertThat(failures()).isEqualTo(failuresBefore + ATTEMPTS);
		assertThat(deadLettered()).isEqualTo(deadLetteredBefore + 1);
		assertThat(processedCount(event.eventId())).isZero();
		assertThat(messageCount(group)).isZero();
		// The queue isn't blocked: the next event is processed.
		MessageCreatedEvent next = eventFor(group);
		publish(next);
		await().atMost(TIMEOUT).until(() -> processedCount(next.eventId()) == 1);
	}

	@Test
	void invalidEventsAreDeadLetteredAtOnceWithoutAnyEffect() {
		UUID group = newGroup();
		MessageCreatedEvent valid = eventFor(group);
		List<String> invalidBodies = List.of(
				"not JSON",
				"[]",
				"{}",
				json(valid).replace("\"conversationId\":\"" + group + "\",", ""),
				json(valid).replace("\"eventType\":\"MessageCreated\"", "\"eventType\":\"MessageDeleted\""),
				json(valid).replace("\"eventVersion\":1", "\"eventVersion\":2"),
				json(valid).replace("\"eventId\":\"" + valid.eventId() + "\"", "\"eventId\":\"not-a-uuid\""));
		double failuresBefore = failures();
		double deadLetteredBefore = deadLettered();

		List<String> messageIds = new ArrayList<>();
		for (String body : invalidBodies) {
			String messageId = UUID.randomUUID().toString();
			messageIds.add(messageId);
			publish(messageId, body);
		}

		List<String> deadLettered = new ArrayList<>();
		for (int i = 0; i < invalidBodies.size(); i++) {
			Message message = rabbitTemplate.receive(MessagingConfig.MESSAGE_COUNTS_DEAD_LETTER_QUEUE,
					TIMEOUT.toMillis());
			assertThat(message).as("dead-lettered message %d", i).isNotNull();
			deadLettered.add(message.getMessageProperties().getMessageId());
		}
		assertThat(deadLettered).containsExactlyInAnyOrderElementsOf(messageIds);
		// Each tried once: retrying wouldn't make it valid.
		assertThat(failures()).isEqualTo(failuresBefore + invalidBodies.size());
		assertThat(deadLettered()).isEqualTo(deadLetteredBefore + invalidBodies.size());
		assertThat(messageCount(group)).isZero();
		assertThat(processedCount(valid.eventId())).isZero();
	}

	@Test
	void anEventAboutAConversationThatDoesNotExistIsDeadLetteredWithoutRetries() {
		MessageCreatedEvent event = eventFor(UUID.randomUUID());
		double failuresBefore = failures();

		publish(event);

		Message deadLetter = rabbitTemplate.receive(MessagingConfig.MESSAGE_COUNTS_DEAD_LETTER_QUEUE,
				TIMEOUT.toMillis());
		assertThat(deadLetter).isNotNull();
		assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
		assertThat(failures()).isEqualTo(failuresBefore + 1);
		// The failed transaction recorded nothing.
		assertThat(processedCount(event.eventId())).isZero();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	private MessageCreatedEvent eventFor(UUID conversationId) {
		return MessageCreatedEvent.of(UUID.randomUUID(), conversationId, UUID.randomUUID(),
				Instant.now().truncatedTo(ChronoUnit.MICROS));
	}

	/** Publishes the event the way the relay does. */
	private void publish(MessageCreatedEvent event) {
		publish(event.eventId().toString(), json(event));
	}

	private void publish(String messageId, String body) {
		MessageProperties properties = new MessageProperties();
		properties.setMessageId(messageId);
		properties.setType(MessageCreatedEvent.EVENT_TYPE);
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		rabbitTemplate.send(MessagingConfig.EVENTS_EXCHANGE, MessageCreatedEvent.ROUTING_KEY,
				new Message(body.getBytes(StandardCharsets.UTF_8), properties));
	}

	private String json(MessageCreatedEvent event) {
		return jsonMapper.writeValueAsString(event);
	}

	private <T> T whileCountingFails(UUID conversationId, int failures, Supplier<T> action) {
		jdbcTemplate.execute("create sequence simulated_counting_failures");
		// A serialization failure: the kind of error that goes away when tried again. The sequence isn't rolled
		// back with the failed transaction, so it counts the attempts.
		jdbcTemplate.execute("""
				create function simulated_counting_failure() returns trigger language plpgsql as $$
				begin
					if nextval('simulated_counting_failures') <= %d then
						raise exception 'simulated transient failure' using errcode = '40001';
					end if;
					return new;
				end
				$$
				""".formatted(failures));
		jdbcTemplate.execute("create trigger simulated_counting_failure before insert or update "
				+ "on conversation_message_counts for each row when (new.conversation_id = '" + conversationId
				+ "') execute function simulated_counting_failure()");
		try {
			return action.get();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_counting_failure on conversation_message_counts");
			jdbcTemplate.execute("drop function simulated_counting_failure()");
			jdbcTemplate.execute("drop sequence simulated_counting_failures");
		}
	}

	private void whileCountingFails(UUID conversationId, int failures, Runnable action) {
		whileCountingFails(conversationId, failures, () -> {
			action.run();
			return null;
		});
	}

	private UUID newGroup() {
		UUID creator = userService.createUser("user-" + UUID.randomUUID() + "@example.com",
				"{bcrypt}not-a-real-hash", "User").id();
		return conversationService.createGroupConversation(creator, "Group", List.of()).id();
	}

	private long messageCount(UUID conversationId) {
		List<Long> counts = jdbcTemplate.queryForList(
				"select message_count from conversation_message_counts where conversation_id = ?", Long.class,
				conversationId);
		return counts.isEmpty() ? 0 : counts.getFirst();
	}

	private int processedCount(UUID eventId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from processed_events where consumer_name = ? and event_id = ?", Integer.class,
				MessageCountService.CONSUMER_NAME, eventId);
	}

	private long queueDepth(String queue) {
		return amqpAdmin.getQueueInfo(queue).getMessageCount();
	}

	private double consumed(String outcome) {
		return meterRegistry.counter("chattywap.events.consumed", "consumer", MessageCountService.CONSUMER_NAME,
				"outcome", outcome).count();
	}

	private double failures() {
		return meterRegistry.counter("chattywap.events.consumer.failures", "consumer",
				MessageCountService.CONSUMER_NAME).count();
	}

	private double deadLettered() {
		return meterRegistry.counter("chattywap.events.dead.lettered", "queue", MessagingConfig.MESSAGE_COUNTS_QUEUE)
				.count();
	}

}
