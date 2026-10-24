package com.adonis.chattywap.messaging;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.MessageService;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.user.UserService;
import com.github.dockerjava.api.DockerClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@link OutboxRelay} against RabbitMQ in a container: events are marked published only once the broker confirmed
 * them, and every other outcome leaves them pending for a later attempt. The tests run the relay themselves (the
 * test profile turns the scheduled relay off) and watch what the broker received through queues of their own,
 * bound to the events exchange.
 * <p>
 * A batch size of 3 gives this class its own application context, broker and database, so that the published
 * events and the paused broker don't reach other tests.
 */
@SpringBootTest(properties = "chattywap.messaging.outbox.batch-size=3")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutboxRelayIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private OutboxEventRepository repository;

	@Autowired
	private MessageService messageService;

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private UserService userService;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private RabbitMQContainer rabbitContainer;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private final List<String> testQueues = new ArrayList<>();

	@AfterEach
	void removeTestQueuesAndEvents() {
		testQueues.forEach(amqpAdmin::deleteQueue);
		// Events with a test routing key would otherwise be retried in later tests.
		jdbcTemplate.update("delete from outbox_events where routing_key like 'test.%'");
	}

	// ---------------------------------------------------------------------------------------------------------
	// Publishing

	@Test
	void aPendingEventIsPublishedAndMarkedPublishedOnceTheBrokerConfirmedIt() {
		String queue = testQueue("message.created");
		UUID group = newGroup();
		MessageResponse message = messageService.sendMessage(creatorOf(group), group, "Hello");
		UUID eventId = eventIdOf(message);
		assertThat(publishedAt(eventId)).isNull();

		relay.publishPending();

		assertThat(publishedAt(eventId)).isNotNull();
		assertThat(attempts(eventId)).isEqualTo(1);
		Message received = rabbitTemplate.receive(queue, TIMEOUT.toMillis());
		assertThat(received).isNotNull();
		MessageProperties properties = received.getMessageProperties();
		assertThat(properties.getMessageId()).isEqualTo(eventId.toString());
		assertThat(properties.getType()).isEqualTo("MessageCreated");
		assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
		assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
		assertThat(properties.getReceivedRoutingKey()).isEqualTo("message.created");
		// The body is the stored payload, exactly.
		assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload(eventId));

		// Published events aren't claimed again.
		relay.publishPending();
		assertThat(rabbitTemplate.receive(queue, 500)).isNull();
	}

	@Test
	void aRunPublishesEveryDueEventBatchAfterBatch() {
		String queue = testQueue("message.created");
		UUID group = newGroup();
		List<UUID> eventIds = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			eventIds.add(eventIdOf(messageService.sendMessage(creatorOf(group), group, "Message " + i)));
		}

		// Batches of 3: three of them, the last one not full.
		relay.publishPending();

		assertThat(eventIds).allSatisfy(eventId -> assertThat(publishedAt(eventId)).isNotNull());
		assertThat(receivedMessageIds(queue)).containsExactlyInAnyOrderElementsOf(ids(eventIds));
	}

	// ---------------------------------------------------------------------------------------------------------
	// Failed attempts

	@Test
	void anEventNoQueueReceivesIsNotMarkedPublished() {
		String routingKey = "test.unrouted." + UUID.randomUUID();
		UUID eventId = insertEvent(routingKey);
		double returnedBefore = failures("returned");

		relay.publishPending();

		// The broker accepted the message but returned it: nobody received it.
		assertThat(publishedAt(eventId)).isNull();
		assertThat(attempts(eventId)).isEqualTo(1);
		assertThat(lastError(eventId)).contains("No queue is bound to routing key " + routingKey).contains("NO_ROUTE");
		assertThat(failures("returned")).isEqualTo(returnedBefore + 1);
		assertThat(isDue(eventId)).isFalse();
	}

	@Test
	void anEventTheBrokerRejectsIsNotMarkedPublished() {
		String routingKey = "test.rejected." + UUID.randomUUID();
		// A queue that refuses every message: the broker answers with a negative acknowledgement.
		Queue full = QueueBuilder.durable("test.full." + UUID.randomUUID()).maxLength(0)
				.overflow(QueueBuilder.Overflow.rejectPublish).build();
		bind(full, routingKey);
		UUID eventId = insertEvent(routingKey);
		double nacksBefore = failures("nack");

		relay.publishPending();

		assertThat(publishedAt(eventId)).isNull();
		assertThat(lastError(eventId)).contains("Broker did not accept the event");
		assertThat(failures("nack")).isEqualTo(nacksBefore + 1);
	}

	@Test
	void anEventTheBrokerDoesNotConfirmInTimeIsNotMarkedPublished() {
		String queue = testQueue("message.created");
		UUID group = newGroup();
		UUID eventId = eventIdOf(messageService.sendMessage(creatorOf(group), group, "Hello"));
		double timeoutsBefore = failures("timeout");
		// A channel opened before the broker freezes, so that sending needs no answer from it.
		rabbitTemplate.execute(channel -> channel.isOpen());

		DockerClient docker = DockerClientFactory.instance().client();
		docker.pauseContainerCmd(rabbitContainer.getContainerId()).exec();
		try {
			relay.publishPending();
		}
		finally {
			docker.unpauseContainerCmd(rabbitContainer.getContainerId()).exec();
		}

		// The send returned without an exception, which proves nothing: without a confirmation the event stays
		// pending.
		assertThat(publishedAt(eventId)).isNull();
		assertThat(lastError(eventId)).startsWith("No confirmation within");
		assertThat(failures("timeout")).isEqualTo(timeoutsBefore + 1);

		// The broker got the message after all, once it resumed; the retry publishes it a second time.
		makeDue(eventId);
		relay.publishPending();
		assertThat(publishedAt(eventId)).isNotNull();
		assertThat(attempts(eventId)).isEqualTo(2);
		assertThat(receivedMessageIds(queue)).containsExactly(eventId.toString(), eventId.toString());
		awaitProcessedOnce(eventId);
	}

	@Test
	void aFailedEventIsRetriedAfterItsDelayAndThenPublished() {
		String routingKey = "test.retried." + UUID.randomUUID();
		UUID eventId = insertEvent(routingKey);
		double retriesBefore = meterRegistry.counter("chattywap.outbox.retried").count();
		relay.publishPending();
		assertThat(publishedAt(eventId)).isNull();

		// Not due before its delay has passed: an immediate run leaves it alone.
		relay.publishPending();
		assertThat(attempts(eventId)).isEqualTo(1);

		// Once a queue receives it and its delay has passed, the next run publishes it.
		String queue = testQueue(routingKey);
		makeDue(eventId);
		relay.publishPending();

		assertThat(publishedAt(eventId)).isNotNull();
		assertThat(attempts(eventId)).isEqualTo(2);
		assertThat(meterRegistry.counter("chattywap.outbox.retried").count()).isEqualTo(retriesBefore + 1);
		// The error of the failed attempt is kept for investigation.
		assertThat(lastError(eventId)).contains("No queue is bound");
		assertThat(receivedMessageIds(queue)).containsExactly(eventId.toString());
	}

	// ---------------------------------------------------------------------------------------------------------
	// Duplicates and concurrency

	@Test
	void anEventWhosePublicationWasNotRecordedIsPublishedAgainAndProcessedOnce() {
		String queue = testQueue("message.created");
		UUID group = newGroup();
		UUID eventId = eventIdOf(messageService.sendMessage(creatorOf(group), group, "Hello"));

		// The broker confirms the event, but recording that fails, as if the relay crashed right after the
		// confirmation.
		whileMarkingPublishedFails(eventId, () -> assertThatThrownBy(relay::publishPending)
				.isInstanceOf(DataAccessException.class));
		assertThat(publishedAt(eventId)).isNull();
		awaitProcessedOnce(eventId);
		double duplicatesBefore = consumed("duplicate");

		// Once the lease has expired, the event is published again.
		makeDue(eventId);
		relay.publishPending();

		assertThat(publishedAt(eventId)).isNotNull();
		assertThat(receivedMessageIds(queue)).containsExactly(eventId.toString(), eventId.toString());
		// The consumer received it twice and counted it once.
		await().atMost(TIMEOUT).until(() -> consumed("duplicate") > duplicatesBefore);
		assertThat(messageCount(group)).isEqualTo(1);
		assertThat(processedCount(eventId)).isEqualTo(1);
	}

	@Test
	void aClaimedEventIsPublishedByAnotherRelayOnceTheClaimsLeaseHasExpired() {
		UUID group = newGroup();
		UUID eventId = eventIdOf(messageService.sendMessage(creatorOf(group), group, "Hello"));

		// A relay claims the event and stops before publishing it.
		assertThat(repository.claimDue(10, Duration.ofMinutes(1).toMillis())).extracting(OutboxEvent::getId)
				.contains(eventId);

		// The lease still holds: no other relay publishes it.
		relay.publishPending();
		assertThat(publishedAt(eventId)).isNull();

		makeDue(eventId);
		relay.publishPending();

		assertThat(publishedAt(eventId)).isNotNull();
		assertThat(attempts(eventId)).isEqualTo(2);
		awaitProcessedOnce(eventId);
	}

	@Test
	void aRelayWhoseLeaseExpiredCannotRecordAFailureForTheEventAnyMore() {
		UUID eventId = insertEvent("test.fenced." + UUID.randomUUID());
		long lease = Duration.ofMinutes(1).toMillis();
		// A first relay's claim (attempt 1) expires, and a second relay claims the event (attempt 2).
		assertThat(repository.claimDue(10, lease)).extracting(OutboxEvent::getId).contains(eventId);
		makeDue(eventId);
		assertThat(repository.claimDue(10, lease)).extracting(OutboxEvent::getId).contains(eventId);
		Object secondLeaseEnd = column(eventId, "next_attempt_at");

		// The first relay's failure comes too late, and doesn't shorten the second relay's lease.
		assertThat(repository.markFailed(eventId, 1, "Too late", 0)).isZero();
		assertThat(column(eventId, "next_attempt_at")).isEqualTo(secondLeaseEnd);
		assertThat(lastError(eventId)).isNull();
		// The second relay's own outcome counts.
		assertThat(repository.markFailed(eventId, 2, "Its own failure", 0)).isEqualTo(1);
		assertThat(lastError(eventId)).isEqualTo("Its own failure");
	}

	@Test
	void concurrentClaimsGetDifferentEventsWithoutWaitingForEachOther() throws Exception {
		Set<UUID> eventIds = new HashSet<>();
		for (int i = 0; i < 6; i++) {
			// Due before anything else, so that the claims below take exactly these.
			UUID eventId = insertEvent("test.claimed." + UUID.randomUUID());
			jdbcTemplate.update("update outbox_events set next_attempt_at = now() - interval '1 hour' where id = ?",
					eventId);
			eventIds.add(eventId);
		}
		CountDownLatch claimed = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		long lease = Duration.ofMinutes(1).toMillis();

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				// The first claim keeps its transaction, and its row locks, open.
				Future<List<UUID>> first = executor.submit(() -> transactionTemplate.execute(status -> {
					List<UUID> ids = repository.claimDue(3, lease).stream().map(OutboxEvent::getId).toList();
					claimed.countDown();
					awaitLatch(commit);
					return ids;
				}));
				awaitLatch(claimed);
				Future<List<UUID>> second = executor.submit(
						() -> repository.claimDue(3, lease).stream().map(OutboxEvent::getId).toList());

				// The second claim skips the locked rows instead of waiting for them.
				List<UUID> secondIds = second.get(10, TimeUnit.SECONDS);
				assertThat(first).isNotDone();
				commit.countDown();
				List<UUID> firstIds = first.get(10, TimeUnit.SECONDS);

				assertThat(firstIds).hasSize(3).doesNotContainAnyElementsOf(secondIds);
				assertThat(secondIds).hasSize(3);
				List<UUID> all = new ArrayList<>(firstIds);
				all.addAll(secondIds);
				assertThat(all).containsExactlyInAnyOrderElementsOf(eventIds);
			}
			finally {
				commit.countDown();
			}
		}
		assertThat(eventIds).allSatisfy(eventId -> assertThat(attempts(eventId)).isEqualTo(1));
	}

	@Test
	void relaysRunningAtTheSameTimePublishEveryEventOnce() throws Exception {
		String queue = testQueue("message.created");
		UUID group = newGroup();
		List<UUID> eventIds = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			eventIds.add(eventIdOf(messageService.sendMessage(creatorOf(group), group, "Message " + i)));
		}
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
			List<Future<Integer>> runs = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				runs.add(executor.submit(() -> {
					start.await();
					return relay.publishPending();
				}));
			}
			start.countDown();
			for (Future<Integer> run : runs) {
				run.get(30, TimeUnit.SECONDS);
			}
		}

		assertThat(eventIds).allSatisfy(eventId -> {
			assertThat(publishedAt(eventId)).isNotNull();
			assertThat(attempts(eventId)).isEqualTo(1);
		});
		assertThat(receivedMessageIds(queue)).containsExactlyInAnyOrderElementsOf(ids(eventIds));
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	/**
	 * A queue of this test's that receives the events published with the routing key. Durable, because RabbitMQ 4
	 * refuses transient queues that aren't exclusive; deleted after the test.
	 */
	private String testQueue(String routingKey) {
		Queue queue = QueueBuilder.durable("test." + UUID.randomUUID()).build();
		bind(queue, routingKey);
		return queue.getName();
	}

	private void bind(Queue queue, String routingKey) {
		amqpAdmin.declareQueue(queue);
		amqpAdmin.declareBinding(BindingBuilder.bind(queue)
				.to(new TopicExchange(MessagingConfig.EVENTS_EXCHANGE))
				.with(routingKey));
		testQueues.add(queue.getName());
	}

	/** The message IDs of everything the queue received, in order. */
	private List<String> receivedMessageIds(String queue) {
		List<String> ids = new ArrayList<>();
		Message message = rabbitTemplate.receive(queue, TIMEOUT.toMillis());
		while (message != null) {
			ids.add(message.getMessageProperties().getMessageId());
			message = rabbitTemplate.receive(queue, 500);
		}
		return ids;
	}

	/** A pending event that isn't a MessageCreated event, published with this routing key. */
	private UUID insertEvent(String routingKey) {
		UUID eventId = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into outbox_events (id, event_type, event_version, routing_key, payload, created_at,
					next_attempt_at)
				values (?, 'TestEvent', 1, ?, ?, now(), now())
				""", eventId, routingKey, "{\"eventId\": \"" + eventId + "\"}");
		return eventId;
	}

	/** Ends the event's lease or retry delay. */
	private void makeDue(UUID eventId) {
		jdbcTemplate.update("update outbox_events set next_attempt_at = now() where id = ?", eventId);
	}

	/** Makes PostgreSQL reject marking the event published while {@code action} runs. */
	private void whileMarkingPublishedFails(UUID eventId, Runnable action) {
		jdbcTemplate.execute("""
				create function simulated_marking_failure() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated failure to record the publication';
				end
				$$
				""");
		jdbcTemplate.execute("create trigger simulated_marking_failure before update on outbox_events for each row "
				+ "when (old.published_at is null and new.published_at is not null and old.id = '" + eventId + "') "
				+ "execute function simulated_marking_failure()");
		try {
			action.run();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_marking_failure on outbox_events");
			jdbcTemplate.execute("drop function simulated_marking_failure()");
		}
	}

	private void awaitProcessedOnce(UUID eventId) {
		await().atMost(TIMEOUT).until(() -> processedCount(eventId) == 1);
	}

	private UUID newGroup() {
		UUID creator = userService.createUser("user-" + UUID.randomUUID() + "@example.com",
				"{bcrypt}not-a-real-hash", "User").id();
		return conversationService.createGroupConversation(creator, "Group", List.of()).id();
	}

	private UUID creatorOf(UUID conversationId) {
		return jdbcTemplate.queryForObject("select created_by from conversations where id = ?", UUID.class,
				conversationId);
	}

	private UUID eventIdOf(MessageResponse message) {
		return jdbcTemplate.queryForObject("select id from outbox_events where payload::jsonb ->> 'messageId' = ?",
				UUID.class, message.id().toString());
	}

	private Object publishedAt(UUID eventId) {
		return column(eventId, "published_at");
	}

	private int attempts(UUID eventId) {
		return (Integer) column(eventId, "attempts");
	}

	private String lastError(UUID eventId) {
		return (String) column(eventId, "last_error");
	}

	private String payload(UUID eventId) {
		return (String) column(eventId, "payload");
	}

	private boolean isDue(UUID eventId) {
		return jdbcTemplate.queryForObject("select next_attempt_at <= now() from outbox_events where id = ?",
				Boolean.class, eventId);
	}

	private Object column(UUID eventId, String column) {
		Map<String, Object> row = jdbcTemplate.queryForMap("select * from outbox_events where id = ?", eventId);
		return row.get(column);
	}

	private long messageCount(UUID conversationId) {
		List<Long> counts = jdbcTemplate.queryForList(
				"select message_count from conversation_message_counts where conversation_id = ?", Long.class,
				conversationId);
		return counts.isEmpty() ? 0 : counts.getFirst();
	}

	private int processedCount(UUID eventId) {
		return jdbcTemplate.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class,
				eventId);
	}

	private double failures(String reason) {
		return meterRegistry.counter("chattywap.outbox.failed", "reason", reason).count();
	}

	private double consumed(String outcome) {
		return meterRegistry.counter("chattywap.events.consumed", "consumer", MessageCountService.CONSUMER_NAME,
				"outcome", outcome).count();
	}

	private static List<String> ids(List<UUID> uuids) {
		return uuids.stream().map(UUID::toString).toList();
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for the other transaction");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
