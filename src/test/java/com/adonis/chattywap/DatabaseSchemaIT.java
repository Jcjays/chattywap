package com.adonis.chattywap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the constraints of the Flyway migration directly with SQL, bypassing the application code, so that
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

	@Test
	void migrationIsApplied() {
		List<String> versions = jdbcTemplate.queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);

		assertThat(versions).contains("1");
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
