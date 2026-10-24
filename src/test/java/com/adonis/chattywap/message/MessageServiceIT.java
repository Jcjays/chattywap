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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Behavior that HTTP tests can't easily provoke: sending a message while its sender loses access, and the number
 * of queries a page of history takes. Calls {@link MessageService} directly.
 * <p>
 * The concurrency tests hold one transaction open at a chosen point and check that the other one waits for it
 * in PostgreSQL ({@code pg_stat_activity}), instead of relying on timing.
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

		// Without the lock, the send would see the membership that the uncommitted leave already deleted, and
		// store the message after Bob had left.
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
		assertThat(jdbcTemplate.queryForObject("select deleted_at is not null from conversations where id = ?",
				Boolean.class, group)).isTrue();
	}

	@Test
	void sendingHoldsUpNeitherOtherSendersNorOtherChangesToTheGroup() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID carol = createUser();
		UUID newcomer = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob, carol)).id();
		CountDownLatch sent = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				Future<MessageResponse> sending = executor.submit(() -> transactionTemplate.execute(status -> {
					MessageResponse message = messageService.sendMessage(bob, group, "Hello");
					status.flush();
					sent.countDown();
					await(commit);
					return message;
				}));
				await(sent);

				// All of these finish while Bob's transaction is still open. Bob's own role changes too: only
				// deleting his membership waits for him.
				Future<?> others = executor.submit(() -> {
					messageService.sendMessage(carol, group, "Hi");
					conversationService.promoteToAdmin(alice, group, bob);
					conversationService.renameGroup(alice, group, "Renamed");
					conversationService.addMembers(alice, group, List.of(newcomer));
					conversationService.leave(carol, group);
				});
				others.get(30, TimeUnit.SECONDS);
				assertThat(sending).isNotDone();

				commit.countDown();
				sending.get(30, TimeUnit.SECONDS);
				assertThat(messageCount(group)).isEqualTo(2);
			}
			finally {
				commit.countDown();
			}
		}
	}

	@Test
	void aRemovalThatHoldsTheConversationLockDoesNotBlockTheSendItWaitsFor() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch store = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				// Bob's membership is locked, but his message isn't written yet.
				Future<MessageResponse> sending = executor.submit(() -> transactionTemplate.execute(status -> {
					conversationService.lockMembership(bob, group);
					locked.countDown();
					await(store);
					return messageService.sendMessage(bob, group, "Last words");
				}));
				await(locked);
				// The removal takes the conversation lock, then waits for Bob's membership.
				Future<?> removing = executor.submit(() -> conversationService.removeMember(alice, group, bob));
				awaitWaitingForLock(removing);

				// Only now is the message written. Its foreign-key check locks the conversation row (FOR KEY SHARE),
				// which the group-change lock (FOR NO KEY UPDATE) allows. With a stronger lock, the two transactions
				// would wait for each other and PostgreSQL would abort one of them.
				store.countDown();

				UUID messageId = sending.get(30, TimeUnit.SECONDS).id();
				removing.get(30, TimeUnit.SECONDS);
				assertThat(count("select count(*) from messages where id = ?", messageId)).isEqualTo(1);
				assertThat(isMember(group, bob)).isFalse();
			}
			finally {
				store.countDown();
			}
		}
	}

	@Test
	void sendingDoesNotWaitForGroupChangesInProgress() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID newcomer = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
		CountDownLatch changed = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			try {
				// Holds the conversation lock, with the changes written but not committed. Bob's own membership row
				// is changed too, by the promotion.
				Future<?> changing = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					conversationService.renameGroup(alice, group, "Renamed");
					conversationService.addMembers(alice, group, List.of(newcomer));
					conversationService.promoteToAdmin(alice, group, bob);
					status.flush();
					changed.countDown();
					await(commit);
				}));
				await(changed);

				Future<MessageResponse> sending = executor.submit(() -> messageService.sendMessage(bob, group, "Hi"));
				sending.get(30, TimeUnit.SECONDS);
				assertThat(changing).isNotDone();

				commit.countDown();
				changing.get(30, TimeUnit.SECONDS);
				assertThat(messageCount(group)).isEqualTo(1);
			}
			finally {
				commit.countDown();
			}
		}
	}

	@Test
	void theMembershipLockNeedsTheCallersTransaction() {
		UUID alice = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();

		// Outside a transaction, the lock would be released as soon as it was taken.
		assertThatThrownBy(() -> conversationService.lockMembership(alice, group))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void aPageTakesTheSameNumberOfQueriesHoweverManySendersItHas() {
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
		// Sending: the membership lock, the insert and the sender.
		assertThat(statementsFor(() -> messageService.sendMessage(alice, tenSenders, "One more"))).isEqualTo(3);
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

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

}
