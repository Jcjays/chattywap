package com.adonis.chattywap.conversation;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.dto.ConversationResponse;
import com.adonis.chattywap.conversation.dto.DirectConversationResult;
import com.adonis.chattywap.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior that HTTP tests can't easily provoke: concurrent requests, failures in the middle of a transaction,
 * and agreement with PostgreSQL's UUID order. Calls {@link ConversationService} directly; each call runs in its
 * own transaction.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ConversationServiceIT {

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private UserService userService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** Spring Boot's default: joins a running transaction or starts one. */
	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	void concurrentRequestsForTheSameDirectConversationCreateOnlyOne() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();

		// Half of the requests come from each side of the pair.
		List<DirectConversationResult> results = runConcurrently(8, attempt -> attempt % 2 == 0
				? conversationService.createDirectConversation(alice, bob)
				: conversationService.createDirectConversation(bob, alice));

		UUID conversationId = results.getFirst().conversation().id();
		assertThat(results).allMatch(result -> result.conversation().id().equals(conversationId));
		assertThat(results).filteredOn(DirectConversationResult::created).hasSize(1);
		assertThat(results).allMatch(result -> result.conversation().members().size() == 2);
		assertThat(count("select count(*) from conversations where created_by in (?, ?)", alice, bob)).isEqualTo(1);
		assertThat(count("select count(*) from conversation_members where conversation_id = ?", conversationId))
				.isEqualTo(2);
	}

	@Test
	void concurrentRequestsFromInsideOtherTransactionsStillCreateOnlyOne() throws Exception {
		UUID alice = createUser();
		UUID bob = createUser();

		// As if another feature called the service from its own @Transactional method. The retry after a lost race
		// must not run in that transaction: PostgreSQL aborts a transaction after a failed insert.
		List<DirectConversationResult> results = runConcurrently(4, attempt -> transactionTemplate.execute(
				status -> conversationService.createDirectConversation(alice, bob)));

		UUID conversationId = results.getFirst().conversation().id();
		assertThat(results).allMatch(result -> result.conversation().id().equals(conversationId));
		assertThat(results).filteredOn(DirectConversationResult::created).hasSize(1);
		assertThat(count("select count(*) from conversations where created_by in (?, ?)", alice, bob)).isEqualTo(1);
	}

	@Test
	void directConversationOrdersUsersTheWayPostgresDoes() {
		// UUID.compareTo puts a UUID starting with "f" before one starting with "0"; PostgreSQL puts it after.
		UUID high = insertUser(UUID.fromString("f" + UUID.randomUUID().toString().substring(1)));
		UUID low = insertUser(UUID.fromString("0" + UUID.randomUUID().toString().substring(1)));

		DirectConversationResult result = conversationService.createDirectConversation(high, low);

		assertThat(result.created()).isTrue();
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"select direct_user1_id, direct_user2_id from conversations where id = ?", result.conversation().id());
		assertThat(row).containsEntry("direct_user1_id", low).containsEntry("direct_user2_id", high);
	}

	@Test
	void concurrentlyAddingTheSameUserAddsThemOnce() throws Exception {
		UUID admin = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(admin, "Group", List.of()).id();

		List<ConversationResponse> results = runConcurrently(6,
				attempt -> conversationService.addMembers(admin, group, List.of(bob)));

		assertThat(results).allMatch(result -> result.members().size() == 2);
		assertThat(count("select count(*) from conversation_members where conversation_id = ? and user_id = ?",
				group, bob)).isEqualTo(1);
	}

	@Test
	void concurrentlyLeavingMembersStillLeaveAnAdministrator() throws Exception {
		// Without the lock on the conversation, the leaving administrator could promote the member who is leaving
		// at the same moment, and the remaining member would end up without an administrator.
		for (int round = 0; round < 5; round++) {
			UUID admin = createUser();
			UUID bob = createUser();
			UUID carol = createUser();
			UUID group = conversationService.createGroupConversation(admin, "Group", List.of(bob, carol)).id();

			runConcurrently(2, attempt -> {
				conversationService.leave(attempt == 0 ? admin : bob, group);
				return null;
			});

			assertThat(jdbcTemplate.queryForList(
					"select user_id from conversation_members where conversation_id = ? and role = 'ADMIN'",
					UUID.class, group)).containsExactly(carol);
		}
	}

	@Test
	void groupCreationIsRolledBackWhenAMembershipCannotBeStored() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID failing = createUser();

		failInsertingMembershipsOf(failing, () ->
				assertThatThrownBy(() -> conversationService.createGroupConversation(alice, "Group", List.of(bob, failing)))
						.hasStackTraceContaining("simulated failure"));

		// The conversation row and the memberships stored before the failure are gone as well.
		assertThat(count("select count(*) from conversations where created_by = ?", alice)).isZero();
		assertThat(count("select count(*) from conversation_members where user_id in (?, ?)", alice, bob)).isZero();
	}

	@Test
	void addingMembersIsRolledBackWhenOneCannotBeStored() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID failing = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();
		OffsetDateTime updatedAt = updatedAt(group);

		failInsertingMembershipsOf(failing, () ->
				assertThatThrownBy(() -> conversationService.addMembers(alice, group, List.of(bob, failing)))
						.hasStackTraceContaining("simulated failure"));

		assertThat(count("select count(*) from conversation_members where conversation_id = ?", group)).isEqualTo(1);
		assertThat(updatedAt(group)).isEqualTo(updatedAt);
	}

	/**
	 * Makes PostgreSQL reject every membership row for {@code userId} while {@code action} runs, so a transaction
	 * fails after it has already written other rows.
	 */
	private void failInsertingMembershipsOf(UUID userId, Runnable action) {
		jdbcTemplate.execute("""
				create function fail_membership_insert() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated failure';
				end
				$$
				""");
		jdbcTemplate.execute("create trigger fail_membership_insert before insert on conversation_members "
				+ "for each row when (new.user_id = '" + userId + "') execute function fail_membership_insert()");
		try {
			action.run();
		}
		finally {
			jdbcTemplate.execute("drop trigger fail_membership_insert on conversation_members");
			jdbcTemplate.execute("drop function fail_membership_insert()");
		}
	}

	private UUID createUser() {
		return userService.createUser("user-" + UUID.randomUUID() + "@example.com", "{bcrypt}not-a-real-hash", "User")
				.id();
	}

	private UUID insertUser(UUID id) {
		jdbcTemplate.update("""
				insert into users (id, email, password_hash, display_name, created_at, updated_at)
				values (?, ?, '{bcrypt}hash', 'User', now(), now())
				""", id, "user-" + id + "@example.com");
		return id;
	}

	private OffsetDateTime updatedAt(UUID conversationId) {
		return jdbcTemplate.queryForObject("select updated_at from conversations where id = ?",
				OffsetDateTime.class, conversationId);
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
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

}
