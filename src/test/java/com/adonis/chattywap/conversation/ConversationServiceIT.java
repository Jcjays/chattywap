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

	@Test
	void concurrentAdditionsCannotTogetherExceedTheMemberLimit() throws Exception {
		UUID admin = createUser();
		// Four places left; four requests of two new users each, so only two requests fit.
		UUID group = conversationService.createGroupConversation(admin, "Group",
				createUsers(ConversationService.MAX_GROUP_MEMBERS - 5)).id();
		List<List<UUID>> batches = List.of(createUsers(2), createUsers(2), createUsers(2), createUsers(2));

		List<Boolean> added = runConcurrently(batches.size(), attempt -> {
			try {
				conversationService.addMembers(admin, group, batches.get(attempt));
				return true;
			}
			catch (GroupMemberLimitExceededException ex) {
				return false;
			}
		});

		assertThat(added).filteredOn(Boolean::booleanValue).hasSize(2);
		assertThat(memberCount(group)).isEqualTo(ConversationService.MAX_GROUP_MEMBERS);
		// Each request added both of its users or neither.
		for (int i = 0; i < batches.size(); i++) {
			int expected = added.get(i) ? 2 : 0;
			assertThat(count("select count(*) from conversation_members where conversation_id = ? and user_id in (?, ?)",
					group, batches.get(i).get(0), batches.get(i).get(1))).isEqualTo(expected);
		}
	}

	@Test
	void concurrentlyDemotingThemselvesTwoAdministratorsLeaveOne() throws Exception {
		// Without the lock, both could see two administrators and both step down.
		for (int round = 0; round < 5; round++) {
			UUID alice = createUser();
			UUID bob = createUser();
			UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
			conversationService.promoteToAdmin(alice, group, bob);
			List<UUID> admins = List.of(alice, bob);

			List<Boolean> demoted = runConcurrently(2, attempt -> {
				UUID self = admins.get(attempt);
				try {
					conversationService.demoteToMember(self, group, self);
					return true;
				}
				catch (LastGroupAdminException ex) {
					return false;
				}
			});

			assertThat(demoted).containsExactlyInAnyOrder(true, false);
			assertThat(adminCount(group)).isEqualTo(1);
		}
	}

	@Test
	void membersAddedWhileTheGroupIsDeletedDoNotSurviveIt() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID alice = createUser();
			UUID bob = createUser();
			UUID newcomer = createUser();
			UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
			conversationService.promoteToAdmin(alice, group, bob);

			// Either the addition runs first and the deletion removes the newcomer again, or the deletion runs
			// first and the addition finds no group.
			runConcurrently(2, attempt -> {
				if (attempt == 0) {
					conversationService.deleteGroup(alice, group);
				}
				else {
					try {
						conversationService.addMembers(bob, group, List.of(newcomer));
					}
					catch (ConversationNotFoundException ex) {
						// The group was deleted first.
					}
				}
				return null;
			});

			assertThat(memberCount(group)).isZero();
			assertThat(isDeleted(group)).isTrue();
		}
	}

	@Test
	void theLastTwoMembersLeavingAtOnceDeleteTheGroup() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID alice = createUser();
			UUID bob = createUser();
			UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();
			List<UUID> members = List.of(alice, bob);

			runConcurrently(2, attempt -> {
				conversationService.leave(members.get(attempt), group);
				return null;
			});

			assertThat(memberCount(group)).isZero();
			assertThat(isDeleted(group)).isTrue();
		}
	}

	@Test
	void deletingAGroupIsRolledBackWhenItCannotBeMarkedDeleted() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();

		// The memberships are deleted first; marking the conversation deleted then fails.
		rejectWhile("update", "conversations", "new.id = '" + group + "' and new.deleted_at is not null", () ->
				assertThatThrownBy(() -> conversationService.deleteGroup(alice, group))
						.hasStackTraceContaining("simulated failure"));

		assertThat(memberCount(group)).isEqualTo(2);
		assertThat(isDeleted(group)).isFalse();
		assertThat(conversationService.getConversation(bob, group).members()).hasSize(2);
	}

	/**
	 * Makes PostgreSQL reject every membership row for {@code userId} while {@code action} runs, so a transaction
	 * fails after it has already written other rows.
	 */
	private void failInsertingMembershipsOf(UUID userId, Runnable action) {
		rejectWhile("insert", "conversation_members", "new.user_id = '" + userId + "'", action);
	}

	/**
	 * Makes PostgreSQL fail every {@code event} ({@code insert}, {@code update}) of a {@code table} row that
	 * matches {@code condition} while {@code action} runs.
	 */
	private void rejectWhile(String event, String table, String condition, Runnable action) {
		jdbcTemplate.execute("""
				create function simulated_failure() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated failure';
				end
				$$
				""");
		jdbcTemplate.execute("create trigger simulated_failure before " + event + " on " + table
				+ " for each row when (" + condition + ") execute function simulated_failure()");
		try {
			action.run();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_failure on " + table);
			jdbcTemplate.execute("drop function simulated_failure()");
		}
	}

	private UUID createUser() {
		return userService.createUser("user-" + UUID.randomUUID() + "@example.com", "{bcrypt}not-a-real-hash", "User")
				.id();
	}

	private List<UUID> createUsers(int count) {
		List<UUID> users = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			users.add(createUser());
		}
		return users;
	}

	private int memberCount(UUID conversationId) {
		return count("select count(*) from conversation_members where conversation_id = ?", conversationId);
	}

	private int adminCount(UUID conversationId) {
		return count("select count(*) from conversation_members where conversation_id = ? and role = 'ADMIN'",
				conversationId);
	}

	private boolean isDeleted(UUID conversationId) {
		return jdbcTemplate.queryForObject("select deleted_at is not null from conversations where id = ?",
				Boolean.class, conversationId);
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
