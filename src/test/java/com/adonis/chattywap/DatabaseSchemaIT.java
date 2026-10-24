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
import java.util.List;
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

		assertThat(versions).contains("1", "2", "3");
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
