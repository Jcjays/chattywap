package com.adonis.chattywap.message;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationNotFoundException;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
import com.adonis.chattywap.user.UserService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionalApplicationListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Behavior that HTTP tests can't easily provoke: acknowledgements that run at the same moment, members removed while
 * they acknowledge, transactions that fail, which changes are announced, and the number of statements unread counts
 * take. Calls {@link ReceiptService} directly.
 * <p>
 * Several tests hold one transaction open at a chosen point and check that the other one waits for it in PostgreSQL
 * ({@code pg_stat_activity}), instead of relying on timing. Announcements are recorded after their transaction
 * committed, the way {@code realtime} receives them, by a listener added to the context for the test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReceiptServiceIT {

	@Autowired
	private ReceiptService receiptService;

	@Autowired
	private ReceiptPositionRepository receiptPositionRepository;

	@Autowired
	private MessageService messageService;

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private UserService userService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** Spring Boot's default: joins a running transaction or starts one. */
	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private ConfigurableApplicationContext context;

	// ---------------------------------------------------------------------------------------------------------
	// Concurrency

	@Test
	void anOutdatedReadUpdateThatWaitsForANewerOneDoesNotMoveThePositionBack() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		List<UUID> messages = sendMessages(alice, group, 5);

		List<ReceiptPositionChangedEvent> events = recordingCommittedEvents(() -> {
			// The newer update has moved the position, but hasn't committed. The outdated one waits for its row and
			// then finds it further than its own message.
			ReceiptPositionResponse outdated = whileHeldOpen(
					() -> receiptService.markRead(bob, group, messages.get(4)),
					() -> receiptService.markRead(bob, group, messages.get(2)));

			assertThat(outdated.lastReadSequenceNumber()).isEqualTo(5);
		});

		assertThat(storedRead(group, bob)).isEqualTo(5);
		// Only the update that moved the position is announced.
		assertThat(events).extracting(event -> event.position().lastReadSequenceNumber()).containsExactly(5L);
	}

	@Test
	void anOutdatedDeliveryAcknowledgementThatWaitsForANewerOneDoesNotMoveThePositionBack() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		List<UUID> messages = sendMessages(alice, group, 3);
		// The row exists already, so the outdated acknowledgement waits for the row lock, not for the insert.
		receiptService.acknowledgeDelivery(bob, group, messages.get(0));

		ReceiptPositionResponse outdated = whileHeldOpen(
				() -> receiptService.acknowledgeDelivery(bob, group, messages.get(2)),
				() -> receiptService.acknowledgeDelivery(bob, group, messages.get(1)));

		assertThat(outdated.lastDeliveredSequenceNumber()).isEqualTo(3);
		assertThat(storedDelivered(group, bob)).isEqualTo(3);
	}

	@Test
	void concurrentAcknowledgementsEndAtTheFurthestPositions() throws Exception {
		Random random = new Random(8);
		for (int round = 0; round < 5; round++) {
			UUID alice = createUser();
			UUID bob = createUser();
			UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
			List<UUID> messages = sendMessages(alice, group, 20);
			// Each attempt reads or acknowledges delivery of a random message, read and delivery mixed.
			int attempts = 12;
			List<Integer> chosen = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				chosen.add(random.nextInt(messages.size()));
			}

			List<ReceiptPositionResponse> results = new ArrayList<>();
			List<ReceiptPositionChangedEvent> events = recordingCommittedEvents(() -> results.addAll(
					runConcurrently(attempts, attempt -> (attempt % 2 == 0)
							? receiptService.markRead(bob, group, messages.get(chosen.get(attempt)))
							: receiptService.acknowledgeDelivery(bob, group, messages.get(chosen.get(attempt))))));

			long furthestRead = 0;
			long furthestDelivered = 0;
			for (int attempt = 0; attempt < attempts; attempt++) {
				long sequenceNumber = chosen.get(attempt) + 1;
				if (attempt % 2 == 0) {
					furthestRead = Math.max(furthestRead, sequenceNumber);
				}
				furthestDelivered = Math.max(furthestDelivered, sequenceNumber);
			}
			assertThat(storedRead(group, bob)).isEqualTo(furthestRead);
			assertThat(storedDelivered(group, bob)).isEqualTo(furthestDelivered);
			// Every answer is a position that was stored at some point: never behind the caller's own message.
			for (int attempt = 0; attempt < attempts; attempt++) {
				ReceiptPositionResponse result = results.get(attempt);
				long sequenceNumber = chosen.get(attempt) + 1;
				assertThat(attempt % 2 == 0 ? result.lastReadSequenceNumber() : result.lastDeliveredSequenceNumber())
						.isGreaterThanOrEqualTo(sequenceNumber);
				assertThat(result.lastReadSequenceNumber()).isLessThanOrEqualTo(result.lastDeliveredSequenceNumber());
			}
			// Each announcement is a move, so there are at most as many as attempts, and the furthest one is among
			// them.
			assertThat(events).hasSizeBetween(1, attempts);
			assertThat(events).extracting(event -> event.position().lastReadSequenceNumber()).contains(furthestRead);
			assertThat(events).extracting(event -> event.position().lastDeliveredSequenceNumber())
					.contains(furthestDelivered);
		}
	}

	@Test
	void aPositionsTimesNeverMoveBack() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		List<UUID> messages = sendMessages(alice, group, 4);
		Instant delivered = receiptService.acknowledgeDelivery(bob, group, messages.get(2)).deliveredAt();
		// An acknowledgement that waited for the row took its time before the one it waited for: earlier than the
		// stored time, for a position further on.
		Instant earlier = delivered.minusSeconds(60);

		transactionTemplate.executeWithoutResult(status -> {
			receiptPositionRepository.advanceDelivered(group, bob, 4, earlier);
			receiptPositionRepository.advanceRead(group, bob, 3, earlier);
		});

		ReceiptPositionResponse position = receiptService.listReceiptPositions(alice, group).members().stream()
				.filter(member -> member.userId().equals(bob)).findFirst().orElseThrow();
		assertThat(position.lastDeliveredSequenceNumber()).isEqualTo(4);
		assertThat(position.lastReadSequenceNumber()).isEqualTo(3);
		// The positions moved, their times stayed: neither is earlier than the delivered time stored before.
		assertThat(position.deliveredAt()).isEqualTo(delivered);
		assertThat(position.readAt()).isEqualTo(delivered);

		transactionTemplate.executeWithoutResult(status ->
				receiptPositionRepository.advanceRead(group, bob, 4, earlier.minusSeconds(60)));
		assertThat(receiptService.listReceiptPositions(alice, group).members().stream()
				.filter(member -> member.userId().equals(bob)).findFirst().orElseThrow().readAt())
				.isEqualTo(delivered);
	}

	@Test
	void aMemberRemovedWhileAcknowledgingIsTreatedAsANonMember() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID carol = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob, carol)).id();
		List<UUID> messages = sendMessages(alice, group, 2);
		// Carol has a position already; Bob has none yet.
		receiptService.acknowledgeDelivery(carol, group, messages.get(0));

		for (UUID member : List.of(bob, carol)) {
			// The removal is written, not committed: the membership check still sees the member, the position's row
			// (inserted, or moved) waits for the membership row and then finds it gone.
			assertThatThrownBy(() -> whileHeldOpen(
					() -> {
						conversationService.removeMember(alice, group, member);
						return null;
					},
					() -> receiptService.markRead(member, group, messages.get(1))))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(ConversationNotFoundException.class);

			assertThat(count("select count(*) from receipt_positions where conversation_id = ? and user_id = ?", group,
					member)).isZero();
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Transactions and announcements

	@Test
	void aPositionWhoseTransactionFailsIsNeitherStoredNorAnnounced() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		List<UUID> messages = sendMessages(alice, group, 2);
		receiptService.markRead(bob, group, messages.get(0));
		int outboxEventsBefore = count("select count(*) from outbox_events");

		List<ReceiptPositionChangedEvent> events = recordingCommittedEvents(() -> whileStoringPositionsFails(group,
				() -> {
					// Fails at commit, after the position was written and its change published in the transaction.
					assertThatThrownBy(() -> receiptService.markRead(bob, group, messages.get(1)))
							.rootCause().hasMessageContaining("simulated failure");
					assertThatThrownBy(() -> receiptService.acknowledgeDelivery(bob, group, messages.get(1)))
							.rootCause().hasMessageContaining("simulated failure");
				}));

		assertThat(storedRead(group, bob)).isEqualTo(1);
		assertThat(storedDelivered(group, bob)).isEqualTo(1);
		assertThat(events).isEmpty();
		assertThat(count("select count(*) from outbox_events")).isEqualTo(outboxEventsBefore);
	}

	@Test
	void receiptsWriteNoOutboxEvents() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		UUID message = sendMessages(alice, group, 1).getFirst();
		int outboxEventsBefore = count("select count(*) from outbox_events");

		receiptService.acknowledgeDelivery(bob, group, message);
		receiptService.markRead(bob, group, message);

		// Positions are synchronous: no consumer needs an event, and real-time delivery doesn't use RabbitMQ.
		assertThat(count("select count(*) from outbox_events")).isEqualTo(outboxEventsBefore);
	}

	@Test
	void onlyChangesAreAnnouncedToTheMembersAtThatMoment() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID carol = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob, carol)).id();
		List<UUID> messages = sendMessages(alice, group, 3);

		List<ReceiptPositionChangedEvent> events = recordingCommittedEvents(() -> {
			receiptService.acknowledgeDelivery(bob, group, messages.get(1));
			// Repeated or outdated: no change, nothing announced.
			receiptService.acknowledgeDelivery(bob, group, messages.get(1));
			receiptService.acknowledgeDelivery(bob, group, messages.get(0));
			// The first read position is a change, even behind the delivered position; repeating it isn't.
			receiptService.markRead(bob, group, messages.get(0));
			receiptService.markRead(bob, group, messages.get(0));
			conversationService.removeMember(alice, group, carol);
			receiptService.markRead(bob, group, messages.get(2));
		});

		assertThat(events).hasSize(3);
		assertThat(events.get(0).position().lastDeliveredSequenceNumber()).isEqualTo(2);
		assertThat(events.get(0).recipientIds()).containsExactlyInAnyOrder(alice, bob, carol);
		assertThat(events.get(1).position().lastReadSequenceNumber()).isEqualTo(1);
		assertThat(events.get(1).position().lastDeliveredSequenceNumber()).isEqualTo(2);
		// After Carol's removal, she isn't a recipient.
		assertThat(events.get(2).position().lastReadSequenceNumber()).isEqualTo(3);
		assertThat(events.get(2).recipientIds()).containsExactlyInAnyOrder(alice, bob);
		// Each announcement is the position as stored and as the acknowledgement returned it.
		assertThat(events.get(2).position()).isEqualTo(receiptService.listReceiptPositions(alice, group).members()
				.stream().filter(position -> position.userId().equals(bob)).findFirst().orElseThrow());
	}

	// ---------------------------------------------------------------------------------------------------------
	// Statements

	@Test
	void unreadCountsOfAListPageTakeTheSameStatementsHoweverManyConversationsItHas() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID oneConversationUser = createUser();
		UUID first = conversationService.createGroupConversation(alice, "First", List.of(oneConversationUser, bob))
				.id();
		receiptService.markRead(bob, first, sendMessages(alice, first, 2).getFirst());
		for (int i = 0; i < 9; i++) {
			UUID group = conversationService.createGroupConversation(alice, "Group " + i, List.of(bob)).id();
			List<UUID> messages = sendMessages(alice, group, 2);
			// Some with a read position, some without.
			if (i % 2 == 0) {
				receiptService.markRead(bob, group, messages.getFirst());
			}
		}
		receiptService.markRead(oneConversationUser, first, sendMessages(alice, first, 1).getFirst());

		long one = statementsFor(() -> conversationService.listConversations(oneConversationUser, 0, 20));
		long ten = statementsFor(() -> conversationService.listConversations(bob, 0, 20));
		long withoutReadPositions = statementsFor(() -> conversationService.listConversations(alice, 0, 20));

		// The page (not full, so without a count) and the unread counts: one statement for the conversations with a
		// read position, and one for those without, if there are any. Groups only, so no other participants.
		assertThat(ten).isEqualTo(withoutReadPositions).isEqualTo(3);
		assertThat(one).isEqualTo(2);
		// One conversation alone: the membership check and one count.
		assertThat(statementsFor(() -> receiptService.getUnreadCount(bob, first))).isEqualTo(2);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	/**
	 * Runs {@code first} in a transaction that stays open after its statements, then {@code second}: the second must
	 * wait for the first in PostgreSQL. Commits the first, and returns the second's result.
	 */
	private <T> T whileHeldOpen(Callable<?> first, Callable<T> second) throws Exception {
		CountDownLatch written = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				Future<?> holding = executor.submit(() -> transactionTemplate.execute(status -> {
					try {
						first.call();
					}
					catch (Exception ex) {
						throw new IllegalStateException(ex);
					}
					status.flush();
					written.countDown();
					await(commit);
					return null;
				}));
				await(written);
				Future<T> waiting = executor.submit(second);

				awaitWaitingForLock(waiting);
				commit.countDown();

				holding.get(30, TimeUnit.SECONDS);
				return waiting.get(30, TimeUnit.SECONDS);
			}
			finally {
				commit.countDown();
			}
		}
	}

	/**
	 * The receipt changes announced while {@code action} ran, in the order their transactions committed, from any
	 * thread. Recorded after the commit, like {@code realtime} receives them: a change whose transaction rolled back
	 * isn't among them.
	 */
	private List<ReceiptPositionChangedEvent> recordingCommittedEvents(Action action) throws Exception {
		List<ReceiptPositionChangedEvent> events = new CopyOnWriteArrayList<>();
		TransactionalApplicationListener<PayloadApplicationEvent<ReceiptPositionChangedEvent>> listener =
				TransactionalApplicationListener.forPayload(events::add);
		context.addApplicationListener(listener);
		try {
			action.run();
		}
		finally {
			context.removeApplicationListener(listener);
		}
		return events;
	}

	private interface Action {

		void run() throws Exception;

	}

	private List<UUID> sendMessages(UUID senderId, UUID conversationId, int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(messageService.sendMessage(senderId, conversationId, "Message " + i).id());
		}
		return ids;
	}

	/**
	 * Makes PostgreSQL reject every receipt position written in the conversation while {@code action} runs. The
	 * trigger is deferred: it fails the transaction when it commits, after all its statements ran.
	 */
	private void whileStoringPositionsFails(UUID conversationId, Runnable action) {
		jdbcTemplate.execute("""
				create function simulated_receipt_failure() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated failure';
				end
				$$
				""");
		jdbcTemplate.execute("create constraint trigger simulated_receipt_failure after insert or update "
				+ "on receipt_positions deferrable initially deferred for each row "
				+ "when (new.conversation_id = '" + conversationId + "') execute function simulated_receipt_failure()");
		try {
			action.run();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_receipt_failure on receipt_positions");
			jdbcTemplate.execute("drop function simulated_receipt_failure()");
		}
	}

	/**
	 * Waits until a database session waits for a lock. Fails if {@code future} completes first: it didn't have to
	 * wait.
	 */
	private void awaitWaitingForLock(Future<?> future) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			assertThat(future).as("finished without waiting for a lock").isNotDone();
			if (count("""
					select count(*) from pg_stat_activity
					where datname = current_database() and wait_event_type = 'Lock'
					""") > 0) {
				return;
			}
			Thread.sleep(10);
		}
		fail("No database session waited for a lock");
	}

	/** How many JDBC statements {@code action} prepares. */
	private long statementsFor(Runnable action) {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		try {
			statistics.clear();
			action.run();
			return statistics.getPrepareStatementCount();
		}
		finally {
			statistics.setStatisticsEnabled(false);
		}
	}

	private static void await(CountDownLatch latch) {
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

	/**
	 * Starts all attempts at the same moment and returns their results. Fails if any attempt fails.
	 */
	private static <T> List<T> runConcurrently(int attempts, IntFunction<T> action) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
			for (int i = 0; i < attempts; i++) {
				int attempt = i;
				Callable<T> task = () -> {
					start.await();
					return action.apply(attempt);
				};
				futures.add(executor.submit(task));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
	}

	private UUID createUser() {
		return userService.createUser("user-" + UUID.randomUUID() + "@example.com", "{bcrypt}not-a-real-hash", "User")
				.id();
	}

	private long storedRead(UUID conversationId, UUID userId) {
		return jdbcTemplate.queryForObject("""
				select coalesce(last_read_sequence_number, 0) from receipt_positions
				where conversation_id = ? and user_id = ?
				""", Long.class, conversationId, userId);
	}

	private long storedDelivered(UUID conversationId, UUID userId) {
		return jdbcTemplate.queryForObject("""
				select last_delivered_sequence_number from receipt_positions where conversation_id = ? and user_id = ?
				""", Long.class, conversationId, userId);
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

}
