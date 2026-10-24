package com.adonis.chattywap.auth;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RefreshTokenRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh-token rotation, reuse detection and logout, against PostgreSQL in a container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RefreshTokenIT {

	private static final String PASSWORD = "correct horse battery staple";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AuthService authService;

	@Test
	void refreshReturnsNewTokensAndRevokesThePresentedToken() throws Exception {
		TokenResponse login = registerAndLogin();

		MvcTestResult result = refresh(login.refreshToken());

		assertThat(result).hasStatusOk();
		TokenResponse refreshed = tokens(result);
		assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
		assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
		assertThat(getMe(refreshed.accessToken())).hasStatusOk();
		assertThat(isTokenRevoked(login.refreshToken())).isTrue();
		assertThat(isTokenRevoked(refreshed.refreshToken())).isFalse();
		assertThat(familyRevokedReason(refreshed.refreshToken())).isNull();
	}

	@Test
	void refreshIgnoresAnInvalidAccessTokenSentAlong() throws Exception {
		// Clients often attach their (by now expired) access token to every request, including the refresh.
		TokenResponse login = registerAndLogin();

		MvcTestResult result = mvc.post().uri("/api/v1/auth/refresh")
				.header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-otherwise-invalid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(new RefreshTokenRequest(login.refreshToken())))
				.exchange();

		assertThat(result).hasStatusOk();
		assertThat(getMe("expired-or-otherwise-invalid")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void eachNewTokenCanBeRefreshedInTurn() throws Exception {
		TokenResponse first = registerAndLogin();
		TokenResponse second = tokens(refresh(first.refreshToken()));
		TokenResponse third = tokens(refresh(second.refreshToken()));

		assertThat(refresh(third.refreshToken())).hasStatusOk();
		assertThat(familyId(first.refreshToken())).isEqualTo(familyId(third.refreshToken()));
	}

	@Test
	void refreshTokenIsStoredOnlyAsSha256Hash() throws Exception {
		TokenResponse login = registerAndLogin();

		Integer storedAsHash = jdbcTemplate.queryForObject(
				"select count(*) from refresh_tokens where token_hash = ?", Integer.class, sha256(login.refreshToken()));
		Integer storedRaw = jdbcTemplate.queryForObject(
				"select count(*) from refresh_tokens where token_hash = ?", Integer.class, login.refreshToken());

		assertThat(storedAsHash).isEqualTo(1);
		assertThat(storedRaw).isZero();
	}

	@Test
	void reusingARotatedTokenIsRejectedAndRevokesTheWholeFamily() throws Exception {
		TokenResponse login = registerAndLogin();
		TokenResponse refreshed = tokens(refresh(login.refreshToken()));

		MvcTestResult reuse = refresh(login.refreshToken());

		assertThat(reuse).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(reuse).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(reuse).bodyJson().extractingPath("$.detail").isEqualTo("Invalid, expired or revoked refresh token.");
		// The newest token of the session stops working as well: whoever holds it may be the attacker.
		assertThat(refresh(refreshed.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(familyRevokedReason(login.refreshToken())).isEqualTo("REUSE_DETECTED");
	}

	@Test
	void reuseDetectionLeavesOtherSessionsOfTheUserAlone() throws Exception {
		String email = registerUser();
		TokenResponse phone = login(email);
		TokenResponse laptop = login(email);
		refresh(phone.refreshToken());

		assertThat(refresh(phone.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);

		assertThat(refresh(laptop.refreshToken())).hasStatusOk();
	}

	@Test
	void concurrentRefreshesWithTheSameTokenSucceedOnlyOnce() throws Exception {
		TokenResponse login = registerAndLogin();
		int attempts = 8;

		// Call the service from several threads at once; each call runs in its own transaction.
		CountDownLatch start = new CountDownLatch(1);
		List<Future<TokenResponse>> futures = new ArrayList<>();
		List<TokenResponse> successes = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
			for (int i = 0; i < attempts; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return authService.refresh(login.refreshToken());
				}));
			}
			start.countDown();
			for (Future<TokenResponse> future : futures) {
				try {
					successes.add(future.get(30, TimeUnit.SECONDS));
				}
				catch (ExecutionException ex) {
					failures.add(ex.getCause());
				}
			}
		}

		assertThat(successes).hasSize(1);
		assertThat(failures).hasSize(attempts - 1).allMatch(InvalidRefreshTokenException.class::isInstance);
		// The losers presented a token that had just been rotated, which looks exactly like a replay, so the
		// session is revoked and even the winner's new token stops working. Clients must not refresh in parallel.
		assertThat(familyRevokedReason(login.refreshToken())).isEqualTo("REUSE_DETECTED");
		assertThat(refresh(successes.getFirst().refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void expiredRefreshTokenIsRejectedWithoutRevokingTheSession() throws Exception {
		TokenResponse login = registerAndLogin();
		jdbcTemplate.update("""
				update refresh_tokens
				set created_at = now() - interval '31 days', expires_at = now() - interval '1 day'
				where token_hash = ?
				""", sha256(login.refreshToken()));

		assertThat(refresh(login.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(familyRevokedReason(login.refreshToken())).isNull();
	}

	@Test
	void unknownRefreshTokenIsRejected() {
		MvcTestResult result = refresh("this-token-was-never-issued");

		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Invalid, expired or revoked refresh token.");
	}

	@Test
	void blankRefreshTokenIsAValidationError() {
		MvcTestResult result = refresh("");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("refreshToken");
	}

	@Test
	void logoutRevokesTheRefreshTokenAndItsSession() throws Exception {
		TokenResponse login = registerAndLogin();

		MvcTestResult result = logout(login.refreshToken());

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(body(result)).isEmpty();
		assertThat(refresh(login.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(isTokenRevoked(login.refreshToken())).isTrue();
		assertThat(familyRevokedReason(login.refreshToken())).isEqualTo("LOGOUT");
	}

	@Test
	void logoutAlsoEndsTheSessionWhenGivenAnOlderToken() throws Exception {
		TokenResponse login = registerAndLogin();
		TokenResponse refreshed = tokens(refresh(login.refreshToken()));

		assertThat(logout(login.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(refresh(refreshed.refreshToken())).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void logoutIsIdempotentAndDoesNotRevealWhetherATokenExists() throws Exception {
		TokenResponse login = registerAndLogin();

		assertThat(logout(login.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(logout(login.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(logout("this-token-was-never-issued")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(familyRevokedReason(login.refreshToken())).isEqualTo("LOGOUT");
	}

	@Test
	void logoutWithoutRefreshTokenIsAValidationError() {
		MvcTestResult result = mvc.post().uri("/api/v1/auth/logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.exchange();

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("refreshToken");
	}

	@Test
	void accessTokenStaysValidAfterLogoutUntilItExpires() throws Exception {
		// Documents a deliberate limitation: access tokens are self-contained JWTs that the server doesn't
		// look up, so logout can't revoke them. Their short lifetime limits the exposure.
		TokenResponse login = registerAndLogin();

		assertThat(logout(login.refreshToken())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(getMe(login.accessToken())).hasStatusOk();
	}

	private TokenResponse registerAndLogin() throws Exception {
		return login(registerUser());
	}

	private String registerUser() {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		assertThat(post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice")))
				.hasStatus(HttpStatus.CREATED);
		return email;
	}

	private TokenResponse login(String email) throws Exception {
		MvcTestResult result = post("/api/v1/auth/login", new LoginRequest(email, PASSWORD));
		assertThat(result).hasStatusOk();
		return tokens(result);
	}

	private MvcTestResult refresh(String refreshToken) {
		return post("/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken));
	}

	private MvcTestResult logout(String refreshToken) {
		return post("/api/v1/auth/logout", new RefreshTokenRequest(refreshToken));
	}

	private MvcTestResult getMe(String accessToken) {
		return mvc.get().uri("/api/v1/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.exchange();
	}

	private MvcTestResult post(String uri, Object body) {
		return mvc.post().uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(body))
				.exchange();
	}

	private TokenResponse tokens(MvcTestResult result) throws Exception {
		return jsonMapper.readValue(body(result), TokenResponse.class);
	}

	private static String body(MvcTestResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private boolean isTokenRevoked(String refreshToken) throws Exception {
		return jdbcTemplate.queryForObject("select revoked_at is not null from refresh_tokens where token_hash = ?",
				Boolean.class, sha256(refreshToken));
	}

	private UUID familyId(String refreshToken) throws Exception {
		return jdbcTemplate.queryForObject("select family_id from refresh_tokens where token_hash = ?",
				UUID.class, sha256(refreshToken));
	}

	private String familyRevokedReason(String refreshToken) throws Exception {
		return jdbcTemplate.queryForObject("""
				select f.revoked_reason from refresh_token_families f
				join refresh_tokens t on t.family_id = f.id
				where t.token_hash = ?
				""", String.class, sha256(refreshToken));
	}

	/** Computed independently of the production code, to check that it really stores SHA-256. */
	private static String sha256(String value) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest);
	}

}
