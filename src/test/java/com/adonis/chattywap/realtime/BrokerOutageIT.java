package com.adonis.chattywap.realtime;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.SendMessageRequest;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real-time delivery and the outbox around a RabbitMQ outage, with the scheduled relay running, over real WebSocket
 * connections: WebSocket delivery and storing messages don't need the broker, and events written while it was down
 * are published, and processed once, after it is back. The consumer never delivers a message to WebSocket clients a
 * second time.
 * <p>
 * The outage stops RabbitMQ's application inside its container ({@code rabbitmqctl stop_app}), so the broker
 * refuses connections and later restarts on the same port. Turning the relay on gives this class its own
 * application context and broker, so the outage reaches no other test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "chattywap.messaging.outbox.relay-enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BrokerOutageIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private UserService userService;

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private RabbitMQContainer rabbitContainer;

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	@Test
	void theScheduledRelayPublishesEveryMessagesEventAndTheConsumerDoesNotDeliverItAgain() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient bobClient = subscribed(bob, group)) {
			UUID messageId = restSend(alice, group, "Hello");
			assertThat(bobClient.nextMessage("messages").body()).contains(messageId.toString());

			// Nobody runs the relay here: the scheduled one does.
			await().atMost(TIMEOUT).until(() -> isPublished(messageId) && processedCount(messageId) == 1);
			assertThat(messageCount(group)).isEqualTo(1);

			// Processing the event delivered nothing: the next frame is the next message.
			restSend(alice, group, "Marker");
			assertThat(bobClient.nextMessage("messages").body()).contains("Marker");
		}
	}

	@Test
	void messagesAreStoredAndDeliveredWhileRabbitMqIsDownAndTheirEventsArePublishedOnceItIsBack() throws Exception {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient bobClient = subscribed(bob, group)) {
			rabbitmqctl("stop_app");
			UUID overRest;
			try {
				// Sending, storing and real-time delivery don't involve the broker.
				overRest = restSend(alice, group, "Sent over REST");
				aliceClient.sendJson("/app/conversations/" + group + "/messages",
						jsonMapper.writeValueAsString(new SendMessageRequest("Sent over STOMP")));
				assertThat(bobClient.nextMessage("messages").body()).contains("Sent over REST");
				assertThat(bobClient.nextMessage("messages").body()).contains("Sent over STOMP");
				UUID overStomp = jdbcTemplate.queryForObject(
						"select id from messages where conversation_id = ? and content = 'Sent over STOMP'", UUID.class,
						group);

				// The relay tries, fails, and keeps the events with their errors.
				await().atMost(TIMEOUT).until(() -> hasFailedAttempt(overRest) && hasFailedAttempt(overStomp));
				assertThat(isPublished(overRest)).isFalse();
				assertThat(isPublished(overStomp)).isFalse();

				// The broker shows as down to operators, but the instance stays alive and ready to take requests.
				assertThat(httpStatus(managementPort, "/actuator/health/rabbit")).isEqualTo(503);
				assertThat(httpStatus(managementPort, "/actuator/health")).isEqualTo(503);
				assertThat(httpStatus(port, "/livez")).isEqualTo(200);
				assertThat(httpStatus(port, "/readyz")).isEqualTo(200);
				// So does the outbox backlog.
				assertThat(meterRegistry.get("chattywap.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(2);
			}
			finally {
				rabbitmqctl("start_app");
			}

			// Once the broker is back, the next attempts publish them, and the consumer processes each once.
			List<UUID> messageIds = jdbcTemplate.queryForList("select id from messages where conversation_id = ?",
					UUID.class, group);
			assertThat(messageIds).hasSize(2).contains(overRest);
			await().atMost(TIMEOUT).until(() -> messageIds.stream()
					.allMatch(id -> isPublished(id) && processedCount(id) == 1));
			assertThat(messageCount(group)).isEqualTo(2);

			restSend(alice, group, "Marker");
			assertThat(bobClient.nextMessage("messages").body()).contains("Marker");
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	private record TestUser(UUID id, String accessToken) {
	}

	/** The status of a GET over real HTTP: the probes and the management port aren't reachable through MockMvc. */
	private static int httpStatus(int port, String path) {
		return RestClient.create().get().uri("http://localhost:" + port + path)
				.exchange((request, response) -> response.getStatusCode().value());
	}

	private void rabbitmqctl(String command) throws Exception {
		ExecResult result = rabbitContainer.execInContainer("rabbitmqctl", command);
		assertThat(result.getExitCode()).as("rabbitmqctl %s: %s", command, result.getStderr()).isZero();
	}

	private boolean isPublished(UUID messageId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"select published_at is not null from outbox_events where payload::jsonb ->> 'messageId' = ?",
				Boolean.class, messageId.toString()));
	}

	private boolean hasFailedAttempt(UUID messageId) {
		Map<String, Object> event = jdbcTemplate.queryForMap(
				"select attempts, last_error from outbox_events where payload::jsonb ->> 'messageId' = ?",
				messageId.toString());
		return (Integer) event.get("attempts") >= 1 && event.get("last_error") != null;
	}

	private int processedCount(UUID messageId) {
		return jdbcTemplate.queryForObject("""
				select count(*) from processed_events p
				join outbox_events e on e.id = p.event_id
				where e.payload::jsonb ->> 'messageId' = ?
				""", Integer.class, messageId.toString());
	}

	private long messageCount(UUID conversationId) {
		return jdbcTemplate.queryForObject(
				"select message_count from conversation_message_counts where conversation_id = ?", Long.class,
				conversationId);
	}

	/** Sends through REST and returns the stored message's ID. */
	private UUID restSend(TestUser caller, UUID conversationId, String content) {
		MvcTestResult result = mvc.post().uri("/api/v1/conversations/{id}/messages", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(new SendMessageRequest(content)))
				.exchange();
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsByteArray()).get("id")
				.asString());
	}

	/** A connection subscribed to the conversation's messages as {@code "messages"}, ready to receive. */
	private StompTestClient subscribed(TestUser user, UUID conversationId) {
		StompTestClient client = StompTestClient.connected(port, user.accessToken());
		client.subscribe("messages", "/topic/conversations/" + conversationId + "/messages");
		client.sync();
		return client;
	}

	private TestUser createUser(String displayName) {
		UserResponse user = userService.createUser("user-" + UUID.randomUUID() + "@example.com",
				"{bcrypt}not-a-real-hash", displayName);
		return new TestUser(user.id(), accessToken(user.id()));
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

	private UUID createGroup(TestUser creator, TestUser... members) {
		return conversationService.createGroupConversation(creator.id(), "Group",
				Arrays.stream(members).map(TestUser::id).toList()).id();
	}

}
