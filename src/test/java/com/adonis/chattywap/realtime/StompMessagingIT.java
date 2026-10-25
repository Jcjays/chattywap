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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sending and receiving messages over STOMP, through real WebSocket connections to the running server, against
 * PostgreSQL in a container: messages are stored before they are delivered, delivered once and only to their
 * conversation, never delivered if they couldn't be stored, and stay in the history for clients that missed them.
 * <p>
 * Like {@link StompSecurityIT}, the tests show that something did <em>not</em> arrive with a marker message sent
 * afterwards, never by sleeping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StompMessagingIT {

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
	// Storing and delivering

	@Test
	void aMessageIsStoredAndThenDeliveredToTheConversationsSubscribersTheSenderIncluded() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient aliceClient = subscribed(alice, group);
				StompTestClient bobClient = subscribed(bob, group)) {
			aliceClient.sendJson(send(group), json("Hello, Bob!"));

			Frame toBob = bobClient.nextMessage("messages");
			Frame toAlice = aliceClient.nextMessage("messages");

			assertThat(toBob.header("destination")).isEqualTo(topic(group));
			assertThat(toBob.header("content-type")).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
			assertThat(json(toAlice)).isEqualTo(json(toBob));
			JsonNode message = json(toBob);
			assertThat(message.get("conversationId").asString()).isEqualTo(group.toString());
			assertThat(message.get("sender").get("id").asString()).isEqualTo(alice.id().toString());
			assertThat(message.get("sender").get("displayName").asString()).isEqualTo("Alice");
			assertThat(message.get("content").asString()).isEqualTo("Hello, Bob!");
			assertThat(toBob.body()).doesNotContain("@example.com");

			// Already stored when it was delivered, with the ID and time the server gave it.
			UUID id = UUID.fromString(message.get("id").asString());
			Map<String, Object> row = jdbcTemplate.queryForMap(
					"select conversation_id, sender_id, content, created_at from messages where id = ?", id);
			assertThat(row).containsEntry("conversation_id", group).containsEntry("sender_id", alice.id())
					.containsEntry("content", "Hello, Bob!");
			assertThat(jdbcTemplate.queryForObject("select created_at from messages where id = ?",
					OffsetDateTime.class, id).toInstant())
					.isEqualTo(Instant.parse(message.get("createdAt").asString()));

			// The same JSON as the history entry, which stays the record of what was sent.
			assertThat(historyOf(bob, group).get(0)).isEqualTo(message);
			// Its event carries the ID the STOMP frame had in the logs, as REST requests' events do.
			assertThat(jdbcTemplate.queryForObject(
					"select correlation_id from outbox_events where payload::jsonb ->> 'messageId' = ?", String.class,
					id.toString())).matches("[0-9a-f-]{36}");
		}
	}

	@Test
	void aMessageThatCannotBeStoredIsNotDelivered() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		Instant activityBefore = updatedAt(group);

		try (StompTestClient aliceClient = subscribed(alice, group);
				StompTestClient bobClient = subscribed(bob, group)) {
			// PostgreSQL rejects the message when its transaction commits, after everything was written.
			JsonNode error = whileStoringMessagesFails(group, () -> {
				aliceClient.sendJson(send(group), json("Lost"));
				return json(aliceClient.nextError());
			});

			// The sender learns that it wasn't stored, without details.
			assertThat(error.get("status").asInt()).isEqualTo(500);
			assertThat(error.get("detail").asString()).isEqualTo("An unexpected error occurred.");
			assertThat(error.toString()).doesNotContainIgnoringCase("simulated").doesNotContainIgnoringCase("sql");
			assertThat(messageCount(group)).isZero();
			assertThat(updatedAt(group)).isEqualTo(activityBefore);

			// Nobody received it: the next thing anyone gets is the marker.
			restSend(alice, group, "Marker");
			assertThat(content(bobClient.nextMessage("messages"))).isEqualTo("Marker");
			assertThat(content(aliceClient.nextMessage("messages"))).isEqualTo("Marker");
		}
	}

	@Test
	void messagesAreDeliveredOnlyToTheirOwnConversationsTopic() {
		TestUser alice = createUser("Alice");
		TestUser carol = createUser("Carol");
		UUID first = createGroup(alice);
		UUID second = createGroup(alice, carol);

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient carolClient = subscribed(carol, second)) {
			aliceClient.subscribe("first", topic(first));
			aliceClient.subscribe("second", topic(second));
			aliceClient.sync();

			restSend(alice, first, "To the first");
			restSend(alice, second, "To the second");

			Frame toFirst = aliceClient.nextMessage("first");
			assertThat(toFirst.header("destination")).isEqualTo(topic(first));
			assertThat(content(toFirst)).isEqualTo("To the first");
			assertThat(content(aliceClient.nextMessage("second"))).isEqualTo("To the second");
			// Carol, who is only in the second group, gets nothing from the first.
			assertThat(content(carolClient.nextMessage("messages"))).isEqualTo("To the second");
		}
	}

	@Test
	void eachMessageIsDeliveredOnceWhetherItWasSentThroughRestOrStomp() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient bobClient = subscribed(bob, group)) {
			MvcTestResult created = restSend(alice, group, "Through REST");
			Frame delivered = bobClient.nextMessage("messages");
			// The broadcast is the REST response, exactly.
			assertThat(json(delivered)).isEqualTo(jsonMapper.readTree(body(created)));

			aliceClient.sendJson(send(group), json("Through STOMP"));
			assertThat(content(bobClient.nextMessage("messages"))).isEqualTo("Through STOMP");

			// Neither came twice.
			restSend(alice, group, "Marker");
			assertThat(content(bobClient.nextMessage("messages"))).isEqualTo("Marker");
			bobClient.sync();
		}
		assertThat(messageCount(group)).isEqualTo(3);
	}

	@Test
	void invalidMessagesAreAnsweredToTheSenderAndTheConnectionStaysOpen() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		int max = SendMessageRequest.MAX_CONTENT_LENGTH;

		List<String> invalidContent = List.of("{}", "{\"content\": null}", "{\"content\": \"\"}",
				"{\"content\": \"   \"}", json("a".repeat(max + 1)), "{\"content\": \"a\\u0000b\"}",
				"{\"content\": \"\\uD83D\"}");
		List<String> unreadable = List.of("", "{", "not json", "[]", "null");

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient bobClient = subscribed(bob, group)) {
			for (String payload : invalidContent) {
				aliceClient.sendJson(send(group), payload);

				JsonNode error = json(aliceClient.nextError());
				assertThat(error.get("status").asInt()).as(payload).isEqualTo(400);
				assertThat(error.get("detail").asString()).as(payload).isEqualTo("Request validation failed.");
				assertThat(fields(error)).as(payload).containsOnly("content");
				assertThat(error.get("instance").asString()).as(payload).isEqualTo(send(group));
				// Rejected values are never echoed.
				assertThat(error.toString()).as(payload).doesNotContain("aaaa");
			}
			for (String payload : unreadable) {
				aliceClient.sendJson(send(group), payload);

				JsonNode error = json(aliceClient.nextError());
				assertThat(error.get("status").asInt()).as(payload).isEqualTo(400);
				assertThat(error.get("detail").asString()).as(payload).isEqualTo("Failed to read message payload.");
				assertThat(error.toString()).as(payload).doesNotContain("not json").doesNotContainIgnoringCase("jackson");
			}

			// Still connected, and the longest valid message goes through.
			aliceClient.sendJson(send(group), json("a".repeat(max)));
			assertThat(content(bobClient.nextMessage("messages"))).hasSize(max);
			// Errors went to Alice only.
			bobClient.sync();
		}
		assertThat(messageCount(group)).isEqualTo(1);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Order and concurrency

	@Test
	void messagesFromOneConnectionAreStoredAndDeliveredInTheOrderTheyWereSent() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		int count = 30;

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient bobClient = subscribed(bob, group)) {
			// Sent back to back, without waiting for anything in between.
			for (int i = 1; i <= count; i++) {
				aliceClient.sendJson(send(group), json("Message " + i));
			}

			List<String> received = new ArrayList<>();
			for (int i = 1; i <= count; i++) {
				received.add(content(bobClient.nextMessage("messages")));
			}
			List<String> sent = new ArrayList<>();
			for (int i = 1; i <= count; i++) {
				sent.add("Message " + i);
			}
			assertThat(received).containsExactlyElementsOf(sent);
			// The history is newest first.
			assertThat(contents(historyOf(bob, group))).containsExactlyElementsOf(sent.reversed());
		}
	}

	@Test
	void concurrentSendersAreAllStoredAndEachMessageIsDeliveredOnce() throws Exception {
		int senders = 5;
		int perSender = 10;
		TestUser alice = createUser("Alice");
		List<TestUser> members = new ArrayList<>();
		for (int i = 0; i < senders; i++) {
			members.add(createUser("Sender " + i));
		}
		UUID group = createGroup(alice, members.toArray(TestUser[]::new));

		List<StompTestClient> clients = new ArrayList<>();
		try (StompTestClient observer = subscribed(alice, group)) {
			for (TestUser member : members) {
				clients.add(StompTestClient.connected(port, member.accessToken()));
			}
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService executor = Executors.newFixedThreadPool(senders)) {
				List<Future<?>> sending = new ArrayList<>();
				for (int s = 0; s < senders; s++) {
					StompTestClient client = clients.get(s);
					int sender = s;
					sending.add(executor.submit(() -> {
						start.await();
						for (int i = 0; i < perSender; i++) {
							client.sendJson(send(group), json(sender + ":" + i));
						}
						// Every message of this connection is processed, and none was answered with an error.
						client.sync();
						return null;
					}));
				}
				start.countDown();
				for (Future<?> future : sending) {
					future.get(30, TimeUnit.SECONDS);
				}
			}

			List<JsonNode> received = new ArrayList<>();
			for (int i = 0; i < senders * perSender; i++) {
				received.add(json(observer.nextMessage("messages")));
			}
			restSend(alice, group, "Marker");
			assertThat(content(observer.nextMessage("messages"))).isEqualTo("Marker");

			// Each stored message was delivered exactly once.
			List<String> receivedIds = received.stream().map(message -> message.get("id").asString()).toList();
			assertThat(new HashSet<>(receivedIds)).hasSize(senders * perSender);
			assertThat(receivedIds).containsExactlyInAnyOrderElementsOf(jdbcTemplate.queryForList(
					"select id::text from messages where conversation_id = ? and content <> 'Marker'", String.class,
					group));
			// Stored one after the other: every message has its own time, in the order of the history.
			List<JsonNode> history = historyOf(alice, group).subList(1, senders * perSender + 1).reversed();
			List<Instant> times = history.stream().map(message -> Instant.parse(message.get("createdAt").asString()))
					.toList();
			assertThat(times).isSorted().doesNotHaveDuplicates();
			// Each sender's messages are in the order that sender sent them, in the history and as delivered.
			for (int s = 0; s < senders; s++) {
				String prefix = s + ":";
				List<String> expected = new ArrayList<>();
				for (int i = 0; i < perSender; i++) {
					expected.add(prefix + i);
				}
				assertThat(contents(history).stream().filter(content -> content.startsWith(prefix)))
						.containsExactlyElementsOf(expected);
				assertThat(contents(received).stream().filter(content -> content.startsWith(prefix)))
						.containsExactlyElementsOf(expected);
			}
		}
		finally {
			clients.forEach(StompTestClient::close);
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Missed messages

	@Test
	void messagesSentWhileAClientWasDisconnectedAreInTheHistory() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		String lastSeen;
		try (StompTestClient bobClient = subscribed(bob, group)) {
			restSend(alice, group, "Seen");
			lastSeen = json(bobClient.nextMessage("messages")).get("id").asString();
		}

		// Bob is offline: nothing is kept for him, but the messages are stored.
		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken())) {
			aliceClient.sendJson(send(group), json("Missed 1"));
			aliceClient.sendJson(send(group), json("Missed 2"));
			aliceClient.sync();
		}

		// After reconnecting, Bob subscribes first, then reads the history down to the last message he had.
		try (StompTestClient bobClient = subscribed(bob, group)) {
			List<JsonNode> history = historyOf(bob, group);
			List<String> missed = new ArrayList<>();
			for (JsonNode message : history) {
				if (message.get("id").asString().equals(lastSeen)) {
					break;
				}
				missed.add(message.get("content").asString());
			}
			assertThat(missed).containsExactly("Missed 2", "Missed 1");

			restSend(alice, group, "Live again");
			assertThat(content(bobClient.nextMessage("messages"))).isEqualTo("Live again");
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	private record TestUser(UUID id, String accessToken) {
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

	/** A group with {@code creator} as administrator. */
	private UUID createGroup(TestUser creator, TestUser... members) {
		return conversationService.createGroupConversation(creator.id(), "Group",
				Arrays.stream(members).map(TestUser::id).toList()).id();
	}

	/** A connection subscribed to the conversation's messages as {@code "messages"}, ready to receive. */
	private StompTestClient subscribed(TestUser user, UUID conversationId) {
		StompTestClient client = StompTestClient.connected(port, user.accessToken());
		client.subscribe("messages", topic(conversationId));
		client.sync();
		return client;
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
	private MvcTestResult restSend(TestUser caller, UUID conversationId, String content) {
		MvcTestResult result = mvc.post().uri("/api/v1/conversations/{id}/messages", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(content))
				.exchange();
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return result;
	}

	/** The first page of the history through REST, newest first. */
	private List<JsonNode> historyOf(TestUser caller, UUID conversationId) {
		MvcTestResult result = mvc.get().uri("/api/v1/conversations/{id}/messages?size=100", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.exchange();
		assertThat(result).hasStatusOk();
		List<JsonNode> messages = new ArrayList<>();
		jsonMapper.readTree(body(result)).get("content").forEach(messages::add);
		return messages;
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private JsonNode json(Frame frame) {
		return jsonMapper.readTree(frame.body());
	}

	private String content(Frame message) {
		return json(message).get("content").asString();
	}

	private static List<String> contents(List<JsonNode> messages) {
		return messages.stream().map(message -> message.get("content").asString()).toList();
	}

	/** The fields listed in a validation problem's {@code errors}. */
	private static List<String> fields(JsonNode problem) {
		List<String> fields = new ArrayList<>();
		problem.get("errors").forEach(error -> fields.add(error.get("field").asString()));
		return fields;
	}

	/**
	 * Makes PostgreSQL reject every message stored in the conversation while {@code action} runs. The trigger is
	 * deferred: it fails the transaction when it commits, after all its statements ran.
	 */
	private <T> T whileStoringMessagesFails(UUID conversationId, Supplier<T> action) {
		jdbcTemplate.execute("""
				create function simulated_failure() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated failure';
				end
				$$
				""");
		jdbcTemplate.execute("create constraint trigger simulated_failure after insert on messages "
				+ "deferrable initially deferred for each row when (new.conversation_id = '" + conversationId + "') "
				+ "execute function simulated_failure()");
		try {
			return action.get();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_failure on messages");
			jdbcTemplate.execute("drop function simulated_failure()");
		}
	}

	/** The conversation's activity time, which orders the conversation list. */
	private Instant updatedAt(UUID conversationId) {
		return jdbcTemplate.queryForObject("select updated_at from conversations where id = ?", OffsetDateTime.class,
				conversationId).toInstant();
	}

	private int messageCount(UUID conversationId) {
		return jdbcTemplate.queryForObject("select count(*) from messages where conversation_id = ?", Integer.class,
				conversationId);
	}

}
