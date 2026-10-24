package com.adonis.chattywap.realtime;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.SendMessageRequest;
import com.adonis.chattywap.realtime.StompTestClient.Frame;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.socket.WebSocketHttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may connect, subscribe and send over STOMP, through a real WebSocket connection to the running server, with
 * access tokens validated by the real security configuration, against PostgreSQL in a container.
 * <p>
 * The tests never sleep to show that something did <em>not</em> arrive. A client's frames are delivered in the order
 * they were published, so a test sends a marker message afterwards (through REST, which returns once the message
 * has been published to every connection) and checks that the marker is the next frame.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StompSecurityIT {

	private static final String UNAUTHENTICATED = "Missing, invalid or expired access token.";

	@LocalServerPort
	private int port;

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

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	// ---------------------------------------------------------------------------------------------------------
	// Connecting

	@Test
	void aValidAccessTokenEstablishesASessionForItsUser() {
		TestUser alice = createUser("Alice");

		try (StompTestClient client = StompTestClient.open(port)) {
			Frame connected = client.connect(alice.accessToken());

			assertThat(connected.command()).isEqualTo(StompCommand.CONNECTED);
			assertThat(connected.header("user-name")).isEqualTo(alice.id().toString());
		}
	}

	@Test
	void invalidAccessTokensAreRejected() {
		TestUser alice = createUser("Alice");
		byte[] otherKey = new byte[32];
		new SecureRandom().nextBytes(otherKey);
		JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build();
		Instant issuedAt = Instant.now().minus(Duration.ofMinutes(20));

		Map<String, String> tokens = Map.of(
				"malformed", "not-a-jwt",
				"signed with another key", token(otherEncoder, alice.id(), claims -> {
				}),
				"expired", token(jwtEncoder, alice.id(),
						claims -> claims.issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15)))),
				"another issuer", token(jwtEncoder, alice.id(), claims -> claims.issuer("someone-else")),
				"another audience", token(jwtEncoder, alice.id(), claims -> claims.audience(List.of("some-other-api"))));

		tokens.forEach((description, token) -> {
			try (StompTestClient client = StompTestClient.open(port)) {
				assertRejected(client, client.connect(token), HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
			}
		});
	}

	@Test
	void connectingWithoutABearerTokenIsRejected() {
		TestUser alice = createUser("Alice");

		List<Map<String, String>> headers = List.of(
				Map.of(),
				Map.of("Authorization", ""),
				Map.of("Authorization", "Bearer "),
				Map.of("Authorization", "Basic " + alice.accessToken()),
				Map.of("Authorization", alice.accessToken()),
				// A user ID or login is never taken as the user.
				Map.of("login", alice.id().toString(), "passcode", "anything"));

		for (Map<String, String> header : headers) {
			try (StompTestClient client = StompTestClient.open(port)) {
				assertRejected(client, client.connectWith(header), HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
			}
		}
	}

	@Test
	void theTokenCountsOnlyInTheConnectFrameNotOnTheHandshake() {
		TestUser alice = createUser("Alice");
		Instant issuedAt = Instant.now().minus(Duration.ofMinutes(20));
		String expired = token(jwtEncoder, alice.id(),
				claims -> claims.issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15))));

		// An expired token on the handshake doesn't stop a client that connects with a valid one.
		try (StompTestClient client = StompTestClient.open(port, bearer(expired))) {
			assertThat(client.connect(alice.accessToken()).command()).isEqualTo(StompCommand.CONNECTED);
		}
		// A valid token on the handshake isn't enough.
		try (StompTestClient client = StompTestClient.open(port, bearer(alice.accessToken()))) {
			assertRejected(client, client.connectWith(Map.of()), HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
		}
	}

	@Test
	void framesBeforeConnectAreRejected() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		try (StompTestClient client = StompTestClient.open(port)) {
			client.sendJson(send(group), json("Without connecting"));

			assertRejected(client, client.next(), HttpStatus.BAD_REQUEST, "Invalid STOMP frame.");
		}
		try (StompTestClient client = StompTestClient.open(port)) {
			client.subscribe("messages", topic(group));

			assertRejected(client, client.next(), HttpStatus.BAD_REQUEST, "Invalid STOMP frame.");
		}
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void framesTheServerDoesNotSupportAreRejected() {
		TestUser alice = createUser("Alice");

		for (String command : List.of("ACK", "NACK", "BEGIN", "COMMIT", "ABORT")) {
			try (StompTestClient client = StompTestClient.connected(port, alice.accessToken())) {
				client.send(command, Map.of("id", "1", "transaction", "tx-1"), "");

				assertRejected(client, client.next(), HttpStatus.BAD_REQUEST, "Unsupported STOMP frame.");
			}
		}
	}

	@Test
	void aClientWhoseConnectWasRejectedCannotSend() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		try (StompTestClient client = StompTestClient.open(port)) {
			assertRejected(client, client.connect("not-a-jwt"), HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);

			assertThatThrownBy(() -> client.sendJson(send(group), json("After a rejected CONNECT")))
					.isInstanceOf(IllegalStateException.class);
		}
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void pagesFromOtherOriginsCannotOpenAConnection() {
		WebSocketHttpHeaders foreign = new WebSocketHttpHeaders();
		foreign.setOrigin("https://evil.example");
		WebSocketHttpHeaders sameOrigin = new WebSocketHttpHeaders();
		sameOrigin.setOrigin("http://localhost:" + port);

		assertThatThrownBy(() -> StompTestClient.open(port, foreign)).isInstanceOf(IllegalStateException.class);
		StompTestClient.open(port, sameOrigin).close();
	}

	@Test
	void restEndpointsStillNeedAnAccessToken() {
		// The handshake path is public, nothing else became public with it.
		assertThat(mvc.get().uri("/api/v1/conversations").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.post().uri("/ws").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.get().uri("/ws/anything").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Subscribing

	@Test
	void membersCanSubscribeToTheirConversationsMessages() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID direct = createDirect(alice, bob);

		try (StompTestClient bobClient = StompTestClient.connected(port, bob.accessToken())) {
			bobClient.subscribe("group", topic(group));
			bobClient.subscribe("direct", topic(direct));
			bobClient.sync();

			restSend(alice, group, "Hello, group");
			restSend(alice, direct, "Hello, Bob");

			assertThat(content(bobClient.nextMessage("group"))).isEqualTo("Hello, group");
			assertThat(content(bobClient.nextMessage("direct"))).isEqualTo("Hello, Bob");
		}
	}

	@Test
	void nonMembersCannotSubscribe() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, bob);
		UUID direct = createDirect(alice, bob);
		UUID left = createGroup(alice, mallory);
		conversationService.leave(mallory.id(), left);
		UUID deleted = createGroup(mallory);
		conversationService.deleteGroup(mallory.id(), deleted);

		// The same answer as for a conversation that doesn't exist: nothing reveals that the others do.
		JsonNode unknown = withoutInstance(rejectedSubscription(mallory, topic(UUID.randomUUID())));
		for (UUID conversation : List.of(group, direct, left, deleted)) {
			Frame error = rejectedSubscription(mallory, topic(conversation));

			assertThat(json(error).get("instance").asString()).isEqualTo(topic(conversation));
			assertThat(withoutInstance(error)).isEqualTo(unknown);
		}
		assertThat(unknown.get("status").asInt()).isEqualTo(404);
		assertThat(unknown.get("detail").asString()).isEqualTo("Conversation not found.");
	}

	@Test
	void onlyKnownDestinationsCanBeSubscribedTo() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		List<String> destinations = List.of(
				// Patterns would match every conversation's topic.
				"/topic/conversations/*/messages",
				"/topic/conversations/**",
				"/topic/**",
				// The broker compares destinations as strings: another spelling of the ID would never receive anything.
				"/topic/conversations/" + group.toString().toUpperCase() + "/messages",
				"/topic/conversations/not-a-uuid/messages",
				"/topic/conversations/" + group,
				"/topic/conversations/" + group + "/messages/extra",
				// Other users' error queues, directly and as user destinations.
				"/queue/errors-user" + UUID.randomUUID(),
				"/user/" + bob.id() + "/queue/errors",
				"/user/queue/other",
				"/app/conversations/" + group + "/messages");

		for (String destination : destinations) {
			Frame error = rejectedSubscription(alice, destination);

			assertThat(error.header("message")).as(destination).isEqualTo("Unknown destination.");
			assertThat(json(error).get("status").asInt()).as(destination).isEqualTo(404);
		}
	}

	@Test
	void aRemovedMemberStopsReceivingTheConversationsMessages() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		UUID direct = createDirect(alice, bob);

		try (StompTestClient bobClient = StompTestClient.connected(port, bob.accessToken());
				StompTestClient carolClient = StompTestClient.connected(port, carol.accessToken())) {
			bobClient.subscribe("group", topic(group));
			bobClient.subscribe("direct", topic(direct));
			bobClient.sync();
			carolClient.subscribe("group", topic(group));
			carolClient.sync();

			restSend(alice, group, "Before");
			assertThat(content(bobClient.nextMessage("group"))).isEqualTo("Before");
			assertThat(content(carolClient.nextMessage("group"))).isEqualTo("Before");

			// Bob's subscription stays, but he isn't a recipient any more.
			conversationService.removeMember(alice.id(), group, bob.id());
			restSend(alice, group, "After");
			restSend(alice, direct, "Marker");

			assertThat(content(carolClient.nextMessage("group"))).isEqualTo("After");
			assertThat(content(bobClient.nextMessage("direct"))).isEqualTo("Marker");

			// Nor can he subscribe again.
			bobClient.subscribe("again", topic(group));
			assertRejected(bobClient, bobClient.next(), HttpStatus.NOT_FOUND, "Conversation not found.");
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Sending

	@Test
	void nonMembersCannotSend() {
		TestUser alice = createUser("Alice");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice);
		UUID unknown = UUID.randomUUID();

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient malloryClient = StompTestClient.connected(port, mallory.accessToken())) {
			aliceClient.subscribe("group", topic(group));
			aliceClient.sync();

			malloryClient.sendJson(send(group), json("Not a member"));
			malloryClient.sendJson(send(unknown), json("Not a conversation"));

			// Only Mallory hears about it, the same as from REST, and her connection stays open.
			JsonNode error = json(malloryClient.nextError());
			assertThat(error.get("status").asInt()).isEqualTo(404);
			assertThat(error.get("detail").asString()).isEqualTo("Conversation not found.");
			assertThat(error.get("instance").asString()).isEqualTo(send(group));
			JsonNode unknownError = json(malloryClient.nextError());
			assertThat(unknownError.get("detail").asString()).isEqualTo("Conversation not found.");
			malloryClient.sync();

			restSend(alice, group, "Marker");
			assertThat(content(aliceClient.nextMessage("group"))).isEqualTo("Marker");
			assertThat(count("select count(*) from messages where sender_id = ?", mallory.id())).isZero();
		}
	}

	@Test
	void clientsCannotSendToBrokerDestinations() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		// A forged broadcast: it would reach every subscriber without being stored.
		String forged = "{\"id\": \"" + UUID.randomUUID() + "\", \"conversationId\": \"" + group
				+ "\", \"sender\": {\"id\": \"" + bob.id() + "\", \"displayName\": \"Bob\"}, \"content\": \"Forged\"}";

		List<String> destinations = List.of(
				topic(group),
				"/user/" + bob.id() + "/queue/errors",
				"/queue/errors-user" + UUID.randomUUID(),
				"/app/conversations/" + group,
				"/app/conversations/" + group.toString().toUpperCase() + "/messages",
				"/app/conversations/not-a-uuid/messages");

		try (StompTestClient bobClient = StompTestClient.connected(port, bob.accessToken())) {
			bobClient.subscribe("group", topic(group));
			bobClient.sync();

			for (String destination : destinations) {
				try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken())) {
					aliceClient.sendJson(destination, forged);

					assertRejected(aliceClient, aliceClient.next(), HttpStatus.NOT_FOUND, "Unknown destination.");
				}
			}

			restSend(alice, group, "Marker");
			assertThat(content(bobClient.nextMessage("group"))).isEqualTo("Marker");
			bobClient.sync();
		}
		assertThat(messageCount(group)).isEqualTo(1);
	}

	@Test
	void theSenderIsAlwaysTheConnectionsUser() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient client = StompTestClient.open(port)) {
			// Headers naming another user, at CONNECT and SEND, are ignored, and so are sender IDs in the payload.
			Frame connected = client.connectWith(Map.of("Authorization", "Bearer " + alice.accessToken(),
					"login", bob.id().toString(), "user-name", bob.id().toString()));
			assertThat(connected.header("user-name")).isEqualTo(alice.id().toString());
			client.subscribeToErrors();
			client.subscribe("group", topic(group));
			client.sync();

			client.sendJson(send(group), "{\"content\": \"From Alice\", \"senderId\": \"" + bob.id()
					+ "\", \"sender\": {\"id\": \"" + bob.id() + "\"}}",
					Map.of("user-name", bob.id().toString(), "simpUser", bob.id().toString()));

			JsonNode message = json(client.nextMessage("group"));
			assertThat(message.get("sender").get("id").asString()).isEqualTo(alice.id().toString());
			assertThat(message.get("sender").get("displayName").asString()).isEqualTo("Alice");
		}
		assertThat(count("select count(*) from messages where conversation_id = ? and sender_id = ?", group,
				alice.id())).isEqualTo(1);
		assertThat(count("select count(*) from messages where sender_id = ?", bob.id())).isZero();
	}

	@Test
	void aConnectionCannotSendOrSubscribeOnceItsAccessTokenHasExpired() throws InterruptedException {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		// Token expiry has one minute of tolerance for clock differences, so this token is accepted for 3 more seconds.
		Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(57);
		Instant acceptedUntil = expiresAt.plusSeconds(60);
		String token = token(jwtEncoder, alice.id(),
				claims -> claims.issuedAt(expiresAt.minus(Duration.ofMinutes(15))).expiresAt(expiresAt));

		try (StompTestClient sender = StompTestClient.connected(port, token);
				StompTestClient subscriber = StompTestClient.connected(port, token)) {
			// Waiting is the only way to let a token expire on a connection that is already open.
			Thread.sleep(Math.max(0, Duration.between(Instant.now(), acceptedUntil).toMillis() + 1_000));

			sender.sendJson(send(group), json("Too late"));
			assertRejected(sender, sender.next(), HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
			subscriber.subscribe("group", topic(group));
			assertRejected(subscriber, subscriber.next(), HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
		}
		assertThat(messageCount(group)).isZero();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	private record TestUser(UUID id, String accessToken) {
	}

	private TestUser createUser(String displayName) {
		UserResponse user = userService.createUser("user-" + UUID.randomUUID() + "@example.com",
				"{bcrypt}not-a-real-hash", displayName);
		return new TestUser(user.id(), token(jwtEncoder, user.id(), claims -> {
		}));
	}

	/** Valid for five minutes, with the claims the application issues, unless {@code customizer} changes them. */
	private String token(JwtEncoder encoder, UUID userId, Consumer<JwtClaimsSet.Builder> customizer) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)));
		customizer.accept(claims);
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	private static WebSocketHttpHeaders bearer(String accessToken) {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		return headers;
	}

	/** A group with {@code creator} as administrator. */
	private UUID createGroup(TestUser creator, TestUser... members) {
		return conversationService.createGroupConversation(creator.id(), "Group",
				Arrays.stream(members).map(TestUser::id).toList()).id();
	}

	private UUID createDirect(TestUser caller, TestUser recipient) {
		return conversationService.createDirectConversation(caller.id(), recipient.id()).conversation().id();
	}

	private static String topic(UUID conversationId) {
		return "/topic/conversations/" + conversationId + "/messages";
	}

	private static String send(UUID conversationId) {
		return "/app/conversations/" + conversationId + "/messages";
	}

	private String json(String content) {
		return jsonMapper.writeValueAsString(new SendMessageRequest(content));
	}

	/**
	 * Sends through REST. Returns once the message is stored and has been published to every connection's outbound
	 * queue, so whatever a connection receives next was published after it.
	 */
	private void restSend(TestUser caller, UUID conversationId, String content) {
		assertThat(mvc.post().uri("/api/v1/conversations/{id}/messages", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(content))
				.exchange()).hasStatus(HttpStatus.CREATED);
	}

	/** Subscribes on a new connection and returns the ERROR frame, after which the server closed the connection. */
	private Frame rejectedSubscription(TestUser user, String destination) {
		try (StompTestClient client = StompTestClient.connected(port, user.accessToken())) {
			client.subscribe("rejected", destination);
			Frame error = client.next();
			assertRejected(client, error, HttpStatus.valueOf(json(error).get("status").asInt()),
					error.header("message"));
			return error;
		}
	}

	/** An ERROR frame with a problem detail, after which the server closes the connection. */
	private void assertRejected(StompTestClient client, Frame frame, HttpStatus status, String detail) {
		assertThat(frame.command()).as("frame: %s", frame).isEqualTo(StompCommand.ERROR);
		assertThat(frame.header("message")).isEqualTo(detail);
		assertThat(frame.header("content-type")).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		JsonNode problem = json(frame);
		assertThat(problem.get("status").asInt()).isEqualTo(status.value());
		assertThat(problem.get("title").asString()).isEqualTo(status.getReasonPhrase());
		assertThat(problem.get("detail").asString()).isEqualTo(detail);
		// Nothing internal is passed on.
		assertThat(frame.body()).doesNotContainIgnoringCase("exception").doesNotContainIgnoringCase("channel");
		client.awaitClosed();
	}

	private JsonNode json(Frame frame) {
		return jsonMapper.readTree(frame.body());
	}

	private JsonNode withoutInstance(Frame frame) {
		return jsonMapper.readTree(frame.body().replace(json(frame).get("instance").asString(), ""));
	}

	private String content(Frame message) {
		return json(message).get("content").asString();
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

	private int messageCount(UUID conversationId) {
		return count("select count(*) from messages where conversation_id = ?", conversationId);
	}

}
