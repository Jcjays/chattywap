package com.adonis.chattywap;

import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Requests while PostgreSQL doesn't answer, over real HTTP. The outage pauses the database's container: its process
 * stops, but connections stay open, so a statement already sent would wait for an answer forever without a socket
 * timeout. Requests that need the database must fail quickly with 503, store nothing, and leave the instance alive and
 * ready; once the database answers again, requests succeed without anything being reset.
 * <p>
 * Short timeouts keep the test fast; production uses longer ones (application.yaml). Changing them gives this class its
 * own application context and containers, so pausing the database reaches no other test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.hikari.connection-timeout=1000",
		"spring.datasource.hikari.validation-timeout=500",
		"spring.datasource.hikari.data-source-properties.socketTimeout=2"
})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DatabaseOutageIT {

	/** Connection timeout plus socket timeout, with room to spare: what a failing request may take at most. */
	private static final Duration FAILS_WITHIN = Duration.ofSeconds(8);

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Autowired
	private PostgreSQLContainer postgresContainer;

	@Autowired
	private UserService userService;

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JsonMapper jsonMapper;

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	@Test
	void requestsFailQuicklyWith503WhileTheDatabaseDoesNotAnswerAndSucceedAgainAfterwards() {
		UUID alice = userService.createUser("user-" + UUID.randomUUID() + "@example.com", "{bcrypt}not-a-real-hash",
				"Alice").id();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();
		String token = accessToken(alice);
		assertThat(call("GET", port, "/api/v1/users/me", token, null).status()).isEqualTo(200);

		pause(true);
		try {
			Response read = call("GET", port, "/api/v1/users/me", token, null);
			Response send = call("POST", port, "/api/v1/conversations/" + group + "/messages", token,
					"{\"content\": \"Sent while the database is down\"}");

			for (Response response : List.of(read, send)) {
				assertThat(response.status()).isEqualTo(503);
				assertThat(response.body()).contains("temporarily unavailable").doesNotContain("Exception");
				assertThat(response.duration()).isLessThan(FAILS_WITHIN);
			}
			// The process is fine and the instance keeps its place in the load balancer (README, "Health").
			assertThat(call("GET", port, "/livez", null, null).status()).isEqualTo(200);
			assertThat(call("GET", port, "/readyz", null, null).status()).isEqualTo(200);
			// Operators see the database as down.
			Response health = call("GET", managementPort, "/actuator/health", null, null);
			assertThat(health.status()).isEqualTo(503);
			assertThat(jsonMapper.readTree(health.body()).at("/components/db/status").asString()).isEqualTo("DOWN");
		}
		finally {
			pause(false);
		}

		// The pool replaces the broken connections by itself.
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
				.until(() -> call("GET", port, "/api/v1/users/me", token, null).status() == 200);
		// The send that failed stored nothing, and wrote no event.
		assertThat(jdbcTemplate.queryForObject("select count(*) from messages where conversation_id = ?", Long.class,
				group)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from outbox_events where payload::jsonb ->> 'conversationId' = ?", Long.class,
				group.toString())).isZero();
		assertThat(call("POST", port, "/api/v1/conversations/" + group + "/messages", token,
				"{\"content\": \"Sent after the outage\"}").status()).isEqualTo(201);
	}

	private void pause(boolean paused) {
		String id = postgresContainer.getContainerId();
		if (paused) {
			postgresContainer.getDockerClient().pauseContainerCmd(id).exec();
		}
		else {
			postgresContainer.getDockerClient().unpauseContainerCmd(id).exec();
		}
	}

	private static Response call(String method, int port, String path, String accessToken, String json) {
		long start = System.nanoTime();
		RestClient.RequestBodySpec request = RestClient.create().method(HttpMethod.valueOf(method))
				.uri("http://localhost:" + port + path)
				.headers(headers -> {
					if (accessToken != null) {
						headers.setBearerAuth(accessToken);
					}
				});
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).body(json);
		}
		return request.exchange((sent, response) -> new Response(response.getStatusCode().value(),
				new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
				Duration.ofNanos(System.nanoTime() - start)));
	}

	private record Response(int status, String body, Duration duration) {
	}

	/** Signed by the application's own key, with the claims the application issues. */
	private String accessToken(UUID userId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
