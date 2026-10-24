package com.adonis.chattywap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the constraints of the Flyway migrations directly with SQL, bypassing the application code, so that
 * the database protects the invariants even if a bug in the code doesn't. Hibernate's schema validation
 * at startup (see {@link ChattywapApplicationIT}) checks that the entities match the same migration.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DatabaseSchemaIT {

	private static final String HASH = "a".repeat(64);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Test
	void migrationIsApplied() {
		List<String> versions = jdbcTemplate.queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);

		assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7");
	}

	@Test
	void emailIsUnique() {
		String email = uniqueEmail();
		insertUser(email);

		assertThatThrownBy(() -> insertUser(email))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("users_email_key");
	}

	@Test
	void emailMustBeStoredInNormalizedForm() {
		assertThatThrownBy(() -> insertUser("Mixed-" + UUID.randomUUID() + "@Example.com"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_email_normalized");
		assertThatThrownBy(() -> insertUser(" " + uniqueEmail()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("users_email_normalized");
	}

	@Test
	void refreshTokenFamilyMustBelongToAnExistingUser() {
		assertThatThrownBy(() -> insertFamily(UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("refresh_token_families_user_id_fkey");
	}

	@Test
	void revokedFamilyMustHaveAKnownReason() {
		UUID familyId = insertFamily(insertUser(uniqueEmail()));

		assertThatThrownBy(() -> jdbcTemplate.update(
				"update refresh_token_families set revoked_at = now() where id = ?", familyId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("refresh_token_families_revocation_complete");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"update refresh_token_families set revoked_at = now(), revoked_reason = 'BORED' where id = ?", familyId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("refresh_token_families_revoked_reason_valid");
	}

	@Test
	void refreshTokenMustBelongToAnExistingFamily() {
		assertThatThrownBy(() -> insertToken(UUID.randomUUID(), uniqueHash()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("refresh_tokens_family_id_fkey");
	}

	@Test
	void tokenHashIsUniqueAndLooksLikeSha256Hex() {
		UUID familyId = insertFamily(insertUser(uniqueEmail()));
		String hash = uniqueHash();
		insertToken(familyId, hash);

		assertThatThrownBy(() -> insertToken(familyId, hash))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("refresh_tokens_token_hash_key");
		assertThatThrownBy(() -> insertToken(familyId, "raw-token-instead-of-a-hash"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("refresh_tokens_token_hash_format");
	}

	@Test
	void refreshTokenMustExpireAfterItWasCreated() {
		UUID familyId = insertFamily(insertUser(uniqueEmail()));

		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into refresh_tokens (id, family_id, token_hash, created_at, expires_at)
				values (?, ?, ?, now(), now() - interval '1 second')
				""", UUID.randomUUID(), familyId, uniqueHash()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("refresh_tokens_expires_after_created");
	}

	@Test
	void deletingAUserDeletesTheirFamiliesAndTokens() {
		UUID userId = insertUser(uniqueEmail());
		UUID familyId = insertFamily(userId);
		insertToken(familyId, uniqueHash());

		jdbcTemplate.update("delete from users where id = ?", userId);

		assertThat(count("select count(*) from refresh_token_families where id = ?", familyId)).isZero();
		assertThat(count("select count(*) from refresh_tokens where family_id = ?", familyId)).isZero();
	}

	@Test
	void conversationTypeAndMemberRoleMustBeKnown() {
		UUID userId = insertUser(uniqueEmail());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into conversations (id, type, name, created_by, created_at, updated_at)
				values (?, 'CHANNEL', 'Name', ?, now(), now())
				""", UUID.randomUUID(), userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_type_valid");
		UUID groupId = insertGroup(userId, "Group");
		assertThatThrownBy(() -> insertMember(groupId, userId, "OWNER"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversation_members_role_valid");
	}

	@Test
	void conversationCreatorMustBeAnExistingUser() {
		assertThatThrownBy(() -> insertGroup(UUID.randomUUID(), "Group"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_created_by_fkey");
	}

	@Test
	void groupConversationNeedsANonBlankNameAndNoDirectPair() {
		UUID userId = insertUser(uniqueEmail());
		UUID otherUserId = insertUser(uniqueEmail());

		assertThatThrownBy(() -> insertGroup(userId, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_group_shape");
		assertThatThrownBy(() -> insertGroup(userId, "   "))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_group_shape");
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into conversations (id, type, name, created_by, direct_user1_id, direct_user2_id, created_at, updated_at)
				values (?, 'GROUP', 'Group', ?, least(?, ?), greatest(?, ?), now(), now())
				""", UUID.randomUUID(), userId, userId, otherUserId, userId, otherUserId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_group_shape");
	}

	@Test
	void directConversationNeedsBothUsersAndNoName() {
		UUID userId = insertUser(uniqueEmail());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into conversations (id, type, created_by, direct_user1_id, created_at, updated_at)
				values (?, 'DIRECT', ?, ?, now(), now())
				""", UUID.randomUUID(), userId, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_direct_shape");
		UUID otherUserId = insertUser(uniqueEmail());
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into conversations (id, type, name, created_by, direct_user1_id, direct_user2_id, created_at, updated_at)
				values (?, 'DIRECT', 'Name', ?, least(?, ?), greatest(?, ?), now(), now())
				""", UUID.randomUUID(), userId, userId, otherUserId, userId, otherUserId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_direct_shape");
	}

	@Test
	void directPairIsUniqueAndOrdered() {
		UUID userId = insertUser(uniqueEmail());
		UUID otherUserId = insertUser(uniqueEmail());
		insertDirect(userId, otherUserId);

		assertThatThrownBy(() -> insertDirect(userId, otherUserId))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("conversations_direct_pair_key");
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into conversations (id, type, created_by, direct_user1_id, direct_user2_id, created_at, updated_at)
				values (?, 'DIRECT', ?, greatest(?, ?), least(?, ?), now(), now())
				""", UUID.randomUUID(), userId, userId, otherUserId, userId, otherUserId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_direct_pair_ordered");
	}

	@Test
	void membershipIsUniquePerConversationAndUser() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");
		insertMember(groupId, userId, "ADMIN");

		assertThatThrownBy(() -> insertMember(groupId, userId, "MEMBER"))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("conversation_members_conversation_user_key");
	}

	@Test
	void membershipMustReferenceAnExistingConversationAndUser() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");

		assertThatThrownBy(() -> insertMember(UUID.randomUUID(), userId, "MEMBER"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversation_members_conversation_id_fkey");
		assertThatThrownBy(() -> insertMember(groupId, UUID.randomUUID(), "MEMBER"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversation_members_user_id_fkey");
	}

	@Test
	void deletingAConversationDeletesItsMemberships() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");
		insertMember(groupId, userId, "ADMIN");

		jdbcTemplate.update("delete from conversations where id = ?", groupId);

		assertThat(count("select count(*) from conversation_members where conversation_id = ?", groupId)).isZero();
	}

	@Test
	void usersInConversationsCannotBeDeleted() {
		UUID creatorId = insertUser(uniqueEmail());
		UUID memberId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(creatorId, "Group");
		insertMember(groupId, memberId, "MEMBER");

		assertThatThrownBy(() -> jdbcTemplate.update("delete from users where id = ?", memberId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversation_members_user_id_fkey");
		assertThatThrownBy(() -> jdbcTemplate.update("delete from users where id = ?", creatorId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_created_by_fkey");
	}

	@Test
	void onlyGroupsCanBeDeletedAndNotBeforeTheyWereCreated() {
		UUID userId = insertUser(uniqueEmail());
		UUID otherUserId = insertUser(uniqueEmail());
		UUID directId = insertDirect(userId, otherUserId);
		UUID groupId = insertGroup(userId, "Group");

		assertThatThrownBy(() -> jdbcTemplate.update("update conversations set deleted_at = now() where id = ?", directId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_only_groups_deleted");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"update conversations set deleted_at = created_at - interval '1 second' where id = ?", groupId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("conversations_deleted_after_created");
		assertThat(jdbcTemplate.update("update conversations set deleted_at = created_at where id = ?", groupId))
				.isEqualTo(1);
	}

	@Test
	void messageMustBelongToAnExistingConversationAndSender() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");

		assertThatThrownBy(() -> insertMessage(UUID.randomUUID(), userId, "Hello"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("messages_conversation_id_fkey");
		assertThatThrownBy(() -> insertMessage(groupId, UUID.randomUUID(), "Hello"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("messages_sender_id_fkey");
	}

	@Test
	void messageContentMustNotBeBlank() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");

		for (String blank : List.of("", "   ", " \t\n\r\f\u000B ")) {
			assertThatThrownBy(() -> insertMessage(groupId, userId, blank))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("messages_content_not_blank");
		}
		// Surrounding whitespace is kept.
		UUID messageId = insertMessage(groupId, userId, "\n Hello \n");
		assertThat(jdbcTemplate.queryForObject("select content from messages where id = ?", String.class, messageId))
				.isEqualTo("\n Hello \n");
	}

	@Test
	void messageContentHasAtMost4000Characters() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");

		insertMessage(groupId, userId, "a".repeat(4000));
		// Characters, not UTF-16 code units: the application's limit of 4000 code units always fits.
		insertMessage(groupId, userId, "😀".repeat(4000));
		assertThatThrownBy(() -> insertMessage(groupId, userId, "a".repeat(4001)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("character varying(4000)");
	}

	@Test
	void sequenceNumbersArePositiveAndUniquePerConversation() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");
		UUID otherGroupId = insertGroup(userId, "Other");
		insertMessage(groupId, userId, "First");
		// Each conversation has its own numbers.
		insertMessage(otherGroupId, userId, "First elsewhere");

		assertThatThrownBy(() -> insertMessageNumbered(groupId, userId, 1))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("messages_conversation_id_sequence_number_key");
		for (long invalid : List.of(0L, -1L)) {
			assertThatThrownBy(() -> insertMessageNumbered(groupId, userId, invalid))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("messages_sequence_number_positive");
		}
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into messages (id, conversation_id, sender_id, content, created_at)
				values (?, ?, ?, 'Hello', now())
				""", UUID.randomUUID(), groupId, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sequence_number");
	}

	@Test
	void aReceiptPositionBelongsToAMembershipAndNamesMessagesOfItsConversation() {
		UUID userId = insertUser(uniqueEmail());
		UUID outsiderId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");
		UUID otherGroupId = insertGroup(userId, "Other");
		insertMember(groupId, userId, "ADMIN");
		insertMember(otherGroupId, userId, "ADMIN");
		insertMessage(groupId, userId, "One");
		insertMessage(otherGroupId, userId, "One");
		insertMessage(otherGroupId, userId, "Two");

		assertThatThrownBy(() -> insertReceiptPosition(groupId, outsiderId, 1, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("receipt_positions_member_fkey");
		// Message 2 exists, but in the other conversation.
		assertThatThrownBy(() -> insertReceiptPosition(groupId, userId, 2, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("receipt_positions_delivered_message_fkey");
		// No message has number 0: a member without a read position has null.
		assertThatThrownBy(() -> insertReceiptPosition(groupId, userId, 1, 0L))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("receipt_positions_read_message_fkey");
		insertReceiptPosition(groupId, userId, 1, 1L);
		assertThatThrownBy(() -> insertReceiptPosition(groupId, userId, 1, 1L))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("receipt_positions_pkey");
	}

	@Test
	void aReadPositionIsNeverAheadOfTheDeliveredOneAndHasItsTime() {
		UUID userId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");
		insertMember(groupId, userId, "ADMIN");
		insertMessage(groupId, userId, "One");
		insertMessage(groupId, userId, "Two");

		assertThatThrownBy(() -> insertReceiptPosition(groupId, userId, 1, 2L))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("receipt_positions_read_not_after_delivered");
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into receipt_positions (conversation_id, user_id, last_delivered_sequence_number, delivered_at,
				                               last_read_sequence_number)
				values (?, ?, 2, now(), 1)
				""", groupId, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("receipt_positions_read_complete");
		assertThatThrownBy(() -> jdbcTemplate.update("""
				insert into receipt_positions (conversation_id, user_id, last_delivered_sequence_number, delivered_at,
				                               read_at)
				values (?, ?, 2, now(), now())
				""", groupId, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("receipt_positions_read_complete");
	}

	@Test
	void removingAMembershipRemovesItsReceiptPosition() {
		UUID userId = insertUser(uniqueEmail());
		UUID otherUserId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(userId, "Group");
		insertMember(groupId, userId, "ADMIN");
		insertMember(groupId, otherUserId, "MEMBER");
		insertMessage(groupId, userId, "One");
		insertReceiptPosition(groupId, userId, 1, 1L);
		insertReceiptPosition(groupId, otherUserId, 1, null);

		jdbcTemplate.update("delete from conversation_members where conversation_id = ? and user_id = ?", groupId,
				otherUserId);

		assertThat(count("select count(*) from receipt_positions where conversation_id = ? and user_id = ?", groupId,
				otherUserId)).isZero();
		assertThat(count("select count(*) from receipt_positions where conversation_id = ?", groupId)).isEqualTo(1);
		// Messages that positions name can't be deleted: messages never are.
		assertThatThrownBy(() -> jdbcTemplate.update("delete from messages where conversation_id = ?", groupId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContainingAll("receipt_positions_", "_message_fkey");
	}

	@Test
	void conversationsAndUsersWithMessagesCannotBeDeleted() {
		UUID creatorId = insertUser(uniqueEmail());
		// The database doesn't require senders to be members; that's the application's rule. A sender who
		// belongs to no conversation shows that the message alone keeps the user.
		UUID senderId = insertUser(uniqueEmail());
		UUID groupId = insertGroup(creatorId, "Group");
		insertMessage(groupId, senderId, "Hello");

		assertThatThrownBy(() -> jdbcTemplate.update("delete from conversations where id = ?", groupId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("messages_conversation_id_fkey");
		assertThatThrownBy(() -> jdbcTemplate.update("delete from users where id = ?", senderId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("messages_sender_id_fkey");
	}

	/**
	 * Applies the migrations up to V2 to an empty schema, stores conversations the way the application did before
	 * V3, then applies V3: groups that had lost all their members become deleted, nothing else changes.
	 */
	@Test
	void upgradingToV3DeletesGroupsWithoutMembers() {
		String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try {
			migrate(schema, "2");
			UUID userId = insertUser(schema, uniqueEmail());
			UUID otherUserId = insertUser(schema, uniqueEmail());
			UUID emptyGroup = UUID.randomUUID();
			UUID activeGroup = UUID.randomUUID();
			UUID direct = UUID.randomUUID();
			jdbcTemplate.update("""
					insert into %s.conversations (id, type, name, created_by, created_at, updated_at)
					values (?, 'GROUP', 'Empty', ?, timestamptz '2026-01-01 10:00Z', timestamptz '2026-01-02 10:00Z'),
					       (?, 'GROUP', 'Active', ?, now(), now())
					""".formatted(schema), emptyGroup, userId, activeGroup, userId);
			jdbcTemplate.update("""
					insert into %s.conversations (id, type, created_by, direct_user1_id, direct_user2_id, created_at, updated_at)
					values (?, 'DIRECT', ?, least(?, ?), greatest(?, ?), now(), now())
					""".formatted(schema), direct, userId, userId, otherUserId, userId, otherUserId);
			jdbcTemplate.update("""
					insert into %s.conversation_members (id, conversation_id, user_id, role, joined_at)
					values (?, ?, ?, 'ADMIN', now()), (?, ?, ?, 'MEMBER', now()), (?, ?, ?, 'MEMBER', now())
					""".formatted(schema), UUID.randomUUID(), activeGroup, userId, UUID.randomUUID(), direct, userId,
					UUID.randomUUID(), direct, otherUserId);

			migrate(schema, "3");

			String deletedAt = "select deleted_at from " + schema + ".conversations where id = ?";
			// When the last member left, which is when the group was last updated.
			assertThat(jdbcTemplate.queryForObject(deletedAt, OffsetDateTime.class, emptyGroup).toInstant())
					.isEqualTo(Instant.parse("2026-01-02T10:00:00Z"));
			assertThat(jdbcTemplate.queryForObject(deletedAt, OffsetDateTime.class, activeGroup)).isNull();
			assertThat(jdbcTemplate.queryForObject(deletedAt, OffsetDateTime.class, direct)).isNull();
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	/**
	 * Applies the migrations up to V5 to an empty schema, stores messages the way the application did before V6, then
	 * applies V6: every conversation's messages are numbered from 1 in history order, which is by time, then by ID.
	 */
	@Test
	void upgradingToV6NumbersExistingMessagesInHistoryOrder() {
		String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try {
			migrate(schema, "5");
			UUID userId = insertUser(schema, uniqueEmail());
			UUID group = UUID.randomUUID();
			UUID otherGroup = UUID.randomUUID();
			jdbcTemplate.update("""
					insert into %s.conversations (id, type, name, created_by, created_at, updated_at)
					values (?, 'GROUP', 'Group', ?, now(), now()), (?, 'GROUP', 'Other', ?, now(), now())
					""".formatted(schema), group, userId, otherGroup, userId);
			// Inserted out of order, three of them at the same moment, which their IDs order.
			List<Instant> times = List.of(Instant.parse("2026-01-01T10:00:03Z"), Instant.parse("2026-01-01T10:00:01Z"),
					Instant.parse("2026-01-01T10:00:02Z"), Instant.parse("2026-01-01T10:00:02Z"),
					Instant.parse("2026-01-01T10:00:02Z"));
			List<Map.Entry<Instant, String>> inserted = new ArrayList<>();
			for (Instant time : times) {
				UUID id = UUID.randomUUID();
				jdbcTemplate.update("""
						insert into %s.messages (id, conversation_id, sender_id, content, created_at)
						values (?, ?, ?, 'Hello', ?)
						""".formatted(schema), id, group, userId, OffsetDateTime.ofInstant(time, ZoneOffset.UTC));
				inserted.add(Map.entry(time, id.toString()));
			}
			UUID otherMessage = UUID.randomUUID();
			jdbcTemplate.update("""
					insert into %s.messages (id, conversation_id, sender_id, content, created_at)
					values (?, ?, ?, 'Hello', now())
					""".formatted(schema), otherMessage, otherGroup, userId);

			migrate(schema, "6");

			// PostgreSQL orders UUIDs like their lower-case text, which is how UUID.toString() writes them.
			List<String> historyOrder = inserted.stream()
					.sorted(Map.Entry.<Instant, String>comparingByKey().thenComparing(Map.Entry.comparingByValue()))
					.map(Map.Entry::getValue)
					.toList();
			List<String> byNumber = jdbcTemplate.queryForList("select id::text from " + schema
					+ ".messages where conversation_id = ? order by sequence_number", String.class, group);
			assertThat(byNumber).containsExactlyElementsOf(historyOrder);
			assertThat(jdbcTemplate.queryForList("select sequence_number from " + schema
					+ ".messages where conversation_id = ? order by sequence_number", Long.class, group))
					.containsExactly(1L, 2L, 3L, 4L, 5L);
			assertThat(jdbcTemplate.queryForObject("select sequence_number from " + schema + ".messages where id = ?",
					Long.class, otherMessage)).isEqualTo(1);
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	/** Applies the application's migrations up to {@code version} to {@code schema}, creating it if needed. */
	private void migrate(String schema, String version) {
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.target(version)
				.load()
				.migrate();
	}

	private UUID insertUser(String schema, String email) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into %s.users (id, email, password_hash, display_name, created_at, updated_at)
				values (?, ?, '{bcrypt}hash', 'Alice', now(), now())
				""".formatted(schema), id, email);
		return id;
	}

	private UUID insertGroup(UUID createdBy, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into conversations (id, type, name, created_by, created_at, updated_at)
				values (?, 'GROUP', ?, ?, now(), now())
				""", id, name, createdBy);
		return id;
	}

	/** Stores the pair in the required order, using PostgreSQL's own comparison. */
	private UUID insertDirect(UUID userId, UUID otherUserId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into conversations (id, type, created_by, direct_user1_id, direct_user2_id, created_at, updated_at)
				values (?, 'DIRECT', ?, least(?, ?), greatest(?, ?), now(), now())
				""", id, userId, userId, otherUserId, userId, otherUserId);
		return id;
	}

	private void insertMember(UUID conversationId, UUID userId, String role) {
		jdbcTemplate.update("""
				insert into conversation_members (id, conversation_id, user_id, role, joined_at)
				values (?, ?, ?, ?, now())
				""", UUID.randomUUID(), conversationId, userId, role);
	}

	private void insertMessageNumbered(UUID conversationId, UUID senderId, long sequenceNumber) {
		jdbcTemplate.update("""
				insert into messages (id, conversation_id, sequence_number, sender_id, content, created_at)
				values (?, ?, ?, ?, 'Hello', now())
				""", UUID.randomUUID(), conversationId, sequenceNumber, senderId);
	}

	/** {@code read} may be {@code null}: no read position. The times are set when a position is. */
	private void insertReceiptPosition(UUID conversationId, UUID userId, long delivered, Long read) {
		jdbcTemplate.update("""
				insert into receipt_positions (conversation_id, user_id, last_delivered_sequence_number, delivered_at,
				                               last_read_sequence_number, read_at)
				values (?, ?, ?, now(), ?, case when ?::bigint is null then null else now() end)
				""", conversationId, userId, delivered, read, read);
	}

	/** With the conversation's next sequence number, as the application assigns it. */
	private UUID insertMessage(UUID conversationId, UUID senderId, String content) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into messages (id, conversation_id, sequence_number, sender_id, content, created_at)
				values (?, ?, (select coalesce(max(sequence_number), 0) + 1 from messages where conversation_id = ?),
				        ?, ?, now())
				""", id, conversationId, conversationId, senderId, content);
		return id;
	}

	private UUID insertUser(String email) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into users (id, email, password_hash, display_name, created_at, updated_at)
				values (?, ?, '{bcrypt}hash', 'Alice', now(), now())
				""", id, email);
		return id;
	}

	private UUID insertFamily(UUID userId) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("insert into refresh_token_families (id, user_id, created_at) values (?, ?, now())",
				id, userId);
		return id;
	}

	private void insertToken(UUID familyId, String tokenHash) {
		jdbcTemplate.update("""
				insert into refresh_tokens (id, family_id, token_hash, created_at, expires_at)
				values (?, ?, ?, now(), now() + interval '30 days')
				""", UUID.randomUUID(), familyId, tokenHash);
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniqueHash() {
		return UUID.randomUUID().toString().replace("-", "").repeat(2);
	}

}
