package com.adonis.chattywap.auth;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import com.adonis.chattywap.user.EmailAlreadyRegisteredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration and login through the HTTP API, against PostgreSQL in a container. Tests share one database,
 * so each test uses its own email address instead of relying on an empty table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthControllerIT {

	private static final String PASSWORD = "correct horse battery staple";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private AuthService authService;

	@Test
	void registerCreatesUserAndReturnsProfileWithoutPassword() {
		String email = uniqueEmail();

		MvcTestResult result = post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice"));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
		assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Alice");
		assertThat(result).bodyJson().hasPath("$.id");
		assertThat(result).bodyJson().hasPath("$.createdAt");
		assertThat(result).bodyJson().doesNotHavePath("$.password");
		assertThat(result).bodyJson().doesNotHavePath("$.passwordHash");
	}

	@Test
	void registerStoresOnlyABcryptHashOfThePassword() {
		String email = uniqueEmail();
		assertThat(post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice")))
				.hasStatus(HttpStatus.CREATED);

		String storedHash = jdbcTemplate.queryForObject(
				"select password_hash from users where email = ?", String.class, email);

		assertThat(storedHash).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, storedHash)).isTrue();
	}

	@Test
	void registerNormalizesTheEmailAndTrimsTheDisplayName() {
		String email = uniqueEmail();

		MvcTestResult result = post("/api/v1/auth/register",
				new RegisterRequest(email.toUpperCase(Locale.ROOT), PASSWORD, "  Alice  "));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
		assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Alice");
	}

	@Test
	void displayNameOfOnlyUnicodeWhitespaceIsAValidationError() {
		// " " is an em space. Validation and the strip() before saving treat it the same way, so the name is
		// rejected up front instead of being saved empty and failing the database's not-blank check.
		MvcTestResult result = post("/api/v1/auth/register", new RegisterRequest(uniqueEmail(), PASSWORD, " "));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("displayName");
	}

	@Test
	void registerRejectsDuplicateEmailRegardlessOfCase() {
		String email = uniqueEmail();
		assertThat(post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice")))
				.hasStatus(HttpStatus.CREATED);

		MvcTestResult result = post("/api/v1/auth/register",
				new RegisterRequest(email.toUpperCase(Locale.ROOT), "another password", "Mallory"));

		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("An account with this email address already exists.");
		assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/auth/register");
		assertThat(countUsers(email)).isEqualTo(1);
	}

	@Test
	void concurrentRegistrationsWithTheSameEmailCreateExactlyOneUser() throws Exception {
		String email = uniqueEmail();
		int attempts = 6;

		List<Throwable> failures = runConcurrently(attempts,
				() -> authService.register(new RegisterRequest(email, PASSWORD, "Alice")));

		assertThat(failures).hasSize(attempts - 1).allMatch(EmailAlreadyRegisteredException.class::isInstance);
		assertThat(countUsers(email)).isEqualTo(1);
	}

	@Test
	void registerRejectsInvalidInputWithFieldErrorsButNoRejectedValues() throws Exception {
		MvcTestResult result = post("/api/v1/auth/register", new RegisterRequest("not-an-email", "pw1234", " "));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Request validation failed.");
		assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
				.containsExactly("displayName", "email", "password");
		assertThat(result).bodyJson().doesNotHavePath("$.errors[0].rejectedValue");
		assertThat(body(result)).doesNotContain("pw1234");
	}

	@Test
	void registerRejectsPasswordLongerThan72Bytes() {
		// 40 characters pass @Size(max = 72), but "é" takes 2 bytes in UTF-8: 80 bytes in total.
		String password = "é".repeat(40);

		MvcTestResult result = post("/api/v1/auth/register", new RegisterRequest(uniqueEmail(), password, "Alice"));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("Password must not be longer than 72 bytes when UTF-8 encoded.");
	}

	@Test
	void registerRejectsMalformedJson() {
		MvcTestResult result = mvc.post().uri("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": ")
				.exchange();

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void loginReturnsTokensThatAuthenticateTheUser() throws Exception {
		String email = uniqueEmail();
		assertThat(post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice")))
				.hasStatus(HttpStatus.CREATED);

		// Login normalizes the email the same way registration does.
		MvcTestResult result = post("/api/v1/auth/login", new LoginRequest(email.toUpperCase(Locale.ROOT), PASSWORD));

		assertThat(result).hasStatusOk();
		TokenResponse tokens = jsonMapper.readValue(body(result), TokenResponse.class);
		assertThat(tokens.tokenType()).isEqualTo("Bearer");
		assertThat(tokens.expiresIn()).isEqualTo(15 * 60);
		assertThat(tokens.refreshTokenExpiresIn()).isEqualTo(30 * 24 * 60 * 60);
		assertThat(tokens.refreshToken()).hasSize(43);

		MvcTestResult me = mvc.get().uri("/api/v1/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
				.exchange();
		assertThat(me).hasStatusOk();
		assertThat(me).bodyJson().extractingPath("$.email").isEqualTo(email);
	}

	@Test
	void loginGivesTheSameAnswerForWrongPasswordAndUnknownEmail() throws Exception {
		String email = uniqueEmail();
		assertThat(post("/api/v1/auth/register", new RegisterRequest(email, PASSWORD, "Alice")))
				.hasStatus(HttpStatus.CREATED);

		MvcTestResult wrongPassword = post("/api/v1/auth/login", new LoginRequest(email, "wrong password"));
		MvcTestResult unknownEmail = post("/api/v1/auth/login", new LoginRequest(uniqueEmail(), PASSWORD));

		assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(wrongPassword).bodyJson().extractingPath("$.detail").isEqualTo("Invalid email or password.");
		assertThat(unknownEmail).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(body(unknownEmail)).isEqualTo(body(wrongPassword));
	}

	@Test
	void loginRejectsMissingFields() {
		MvcTestResult result = post("/api/v1/auth/login", new LoginRequest("", ""));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
				.containsExactly("email", "password");
	}

	@Test
	void otherEndpointsRequireAuthenticationByDefault() {
		assertThat(mvc.get().uri("/api/v1/does-not-exist")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.get().uri("/api/v1/auth/login")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	private MvcTestResult post(String uri, Object body) {
		return mvc.post().uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(body))
				.exchange();
	}

	private int countUsers(String email) {
		return jdbcTemplate.queryForObject("select count(*) from users where email = ?", Integer.class, email);
	}

	private static String body(MvcTestResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	/**
	 * Starts all attempts at the same moment and returns the exceptions of those that failed.
	 */
	private static List<Throwable> runConcurrently(int attempts, Runnable action) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		List<Throwable> failures = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
			for (int i = 0; i < attempts; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					action.run();
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				try {
					future.get(30, TimeUnit.SECONDS);
				}
				catch (ExecutionException ex) {
					failures.add(ex.getCause());
				}
			}
		}
		return failures;
	}

}
