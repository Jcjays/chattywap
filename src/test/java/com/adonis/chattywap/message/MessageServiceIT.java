package com.adonis.chattywap.message;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationNotFoundException;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.user.UserService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * Behavior that HTTP tests can't easily provoke: sending messages while the sender loses access, while others send
 * or change the group, and the number of queries sending and paging take. Calls {@link MessageService} directly.
 * <p>
 * Several tests hold one transaction open at a chosen point and check that the other one waits for it in
 * PostgreSQL ({@code pg_stat_activity}), instead of relying on timing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MessageServiceIT {

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

	@Test
	void leavingWaitsForAMessageThatIsBeingSent() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		CountDownLatch sent = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				// Bob's message is written, but its transaction hasn't committed yet.
				Future<MessageResponse> sending = executor.submit(() -> transactionTemplate.execute(status -> {
					MessageResponse message = messageService.sendMessage(bob, group, "Bye");
					status.flush();
					sent.countDown();
					await(commit);
					return message;
				}));
				await(sent);
				Future<?> leaving = executor.submit(() -> conversationService.leave(bob, group));

				awaitWaitingForLock(leaving);
				commit.countDown();

				UUID messageId = sending.get(30, TimeUnit.SECONDS).id();
				leaving.get(30, TimeUnit.SECONDS);
				// The message was stored while Bob was a member; he left afterwards.
				assertThat(count("select count(*) from messages where id = ?", messageId)).isEqualTo(1);
				assertThat(isMember(group, bob)).isFalse();
			}
			finally {
				commit.countDown();
			}
		}
	}

	@Test
	void aMessageSentWhileItsSenderLeavesIsNotStored() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();

		// The send sees the membership that the uncommitted leave already deleted, so only the check under the lock
		// keeps it from storing the message after Bob left.
		assertSendingWaitsAndFails(bob, group, () -> conversationService.leave(bob, group));

		assertThat(messageCount(group)).isZero();
		assertThat(isMember(group, bob)).isFalse();
	}

	@Test
	void aMessageSentWhileItsSenderIsRemovedIsNotStored() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();

		assertSendingWaitsAndFails(bob, group, () -> conversationService.removeMember(alice, group, bob));

		assertThat(messageCount(group)).isZero();
	}

	@Test
	void aMessageSentWhileTheGroupIsDeletedIsNotStored() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();

		assertSendingWaitsAndFails(bob, group, () -> conversationService.deleteGroup(alice, group));

		assertThat(messageCount(group)).isZero();
		// The rejected message recorded no activity after the deletion.
		assertThat(updatedAt(group)).isEqualTo(jdbcTemplate.queryForObject(
				"select deleted_at from conversations where id = ?", OffsetDateTime.class, group).toInstant());
	}

	@Test
	void messagesToOneConversationAreStoredOneAfterTheOther() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		UUID otherGroup = conversationService.createGroupConversation(alice, "Other", List.of(bob)).id();
		CountDownLatch sent = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				Future<MessageResponse> first = executor.submit(() -> transactionTemplate.execute(status -> {
					MessageResponse message = messageService.sendMessage(alice, group, "First");
					status.flush();
					sent.countDown();
					await(commit);
					return message;
				}));
				await(sent);
				Future<MessageResponse> second = executor.submit(() -> messageService.sendMessage(bob, group, "Second"));

				awaitWaitingForLock(second);
				// Other conversations aren't held up.
				messageService.sendMessage(bob, otherGroup, "Elsewhere");
				commit.countDown();

				MessageResponse firstMessage = first.get(30, TimeUnit.SECONDS);
				MessageResponse secondMessage = second.get(30, TimeUnit.SECONDS);
				// The second message took its time only after the first one had committed: the history order is the
				// order in which the messages became visible.
				assertThat(secondMessage.createdAt()).isAfter(firstMessage.createdAt());
				assertThat(updatedAt(group)).isEqualTo(secondMessage.createdAt());
			}
			finally {
				commit.countDown();
			}
		}
	}

	@Test
	void concurrentMessagesAndGroupChangesNeitherDeadlockNorLoseActivity() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID alice = createUser();
			UUID bob = createUser();
			UUID carol = createUser();
			UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob, carol)).id();
			int currentRound = round;

			// A deadlock or any other failure than a rejected message fails runConcurrently.
			List<Boolean> sent = runConcurrently(6, attempt -> switch (attempt) {
				case 0, 1 -> trySend(bob, group, "Bob " + attempt);
				case 2 -> trySend(carol, group, "Carol");
				case 3 -> trySend(alice, group, "Alice");
				case 4 -> {
					conversationService.removeMember(alice, group, bob);
					yield null;
				}
				default -> {
					conversationService.renameGroup(alice, group, "Renamed " + currentRound);
					yield null;
				}
			});

			// Bob's messages were stored before his removal or not at all; Alice's and Carol's always.
			assertThat(sent.subList(2, 4)).containsOnly(true);
			assertThat(count("select count(*) from messages where conversation_id = ? and sender_id = ?", group, bob))
					.isEqualTo((int) sent.subList(0, 2).stream().filter(Boolean::booleanValue).count());
			assertThat(isMember(group, bob)).isFalse();
			// The latest activity is the conversation's activity time: its newest message, or a later change.
			Instant newestMessage = jdbcTemplate.queryForObject(
					"select max(created_at) from messages where conversation_id = ?", OffsetDateTime.class, group)
					.toInstant();
			assertThat(updatedAt(group)).isAfterOrEqualTo(newestMessage);
		}
	}

	@Test
	void recordingActivityNeedsTheCallersTransaction() {
		UUID alice = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();

		// Outside a transaction, the activity time would commit without the message, and the lock would be released
		// before the message is stored.
		assertThatThrownBy(() -> conversationService.recordActivity(alice, group))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void sendingAndPagingTakeTheSameNumberOfQueriesHoweverManySendersThereAre() {
		UUID alice = createUser();
		List<UUID> others = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			others.add(createUser());
		}
		UUID oneSender = conversationService.createGroupConversation(alice, "One sender", List.of()).id();
		UUID tenSenders = conversationService.createGroupConversation(alice, "Ten senders", others).id();
		for (int i = 0; i < 10; i++) {
			messageService.sendMessage(alice, oneSender, "Message " + i);
			messageService.sendMessage(i == 0 ? alice : others.get(i - 1), tenSenders, "Message " + i);
		}

		long byNumberOne = statementsFor(() -> messageService.listMessages(alice, oneSender, 0, 5));
		long byNumberTen = statementsFor(() -> messageService.listMessages(alice, tenSenders, 0, 5));
		String cursorOne = messageService.listMessages(alice, oneSender, 0, 2).nextCursor();
		String cursorTen = messageService.listMessages(alice, tenSenders, 0, 2).nextCursor();
		long byCursorOne = statementsFor(() -> messageService.listMessagesAfter(alice, oneSender, cursorOne, 5));
		long byCursorTen = statementsFor(() -> messageService.listMessagesAfter(alice, tenSenders, cursorTen, 5));

		// By number: the membership check, the page, the count and the senders. By cursor, no count.
		assertThat(byNumberTen).isEqualTo(byNumberOne).isEqualTo(4);
		assertThat(byCursorTen).isEqualTo(byCursorOne).isEqualTo(3);
		// Sending: the membership check, the conversation lock, the membership check under the lock, the sender, the
		// message and the conversation's activity time.
		assertThat(statementsFor(() -> messageService.sendMessage(alice, tenSenders, "One more"))).isEqualTo(6);
	}

	/**
	 * Runs {@code change} in a transaction that stays open, then sends a message from {@code senderId}: the send
	 * must wait for the change and then fail because the sender is no longer a member.
	 */
	private void assertSendingWaitsAndFails(UUID senderId, UUID conversationId, Runnable change) throws Exception {
		CountDownLatch changed = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				// The change is written, but its transaction hasn't committed yet.
				Future<?> changing = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					change.run();
					status.flush();
					changed.countDown();
					await(commit);
				}));
				await(changed);
				Future<MessageResponse> sending =
						executor.submit(() -> messageService.sendMessage(senderId, conversationId, "Am I too late?"));

				awaitWaitingForLock(sending);
				commit.countDown();

				changing.get(30, TimeUnit.SECONDS);
				assertThatThrownBy(() -> sending.get(30, TimeUnit.SECONDS))
						.isInstanceOf(ExecutionException.class)
						.hasCauseInstanceOf(ConversationNotFoundException.class);
			}
			finally {
				commit.countDown();
			}
		}
	}

	/** Whether the message was stored: {@code false} if the sender wasn't, or no longer was, a member. */
	private boolean trySend(UUID senderId, UUID conversationId, String content) {
		try {
			messageService.sendMessage(senderId, conversationId, content);
			return true;
		}
		catch (ConversationNotFoundException ex) {
			return false;
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

	private boolean isMember(UUID conversationId, UUID userId) {
		return count("select count(*) from conversation_members where conversation_id = ? and user_id = ?",
				conversationId, userId) > 0;
	}

	private int messageCount(UUID conversationId) {
		return count("select count(*) from messages where conversation_id = ?", conversationId);
	}

	private Instant updatedAt(UUID conversationId) {
		return jdbcTemplate.queryForObject("select updated_at from conversations where id = ?", OffsetDateTime.class,
				conversationId).toInstant();
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

}
