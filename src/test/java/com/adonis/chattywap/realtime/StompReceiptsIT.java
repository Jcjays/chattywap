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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-time delivery of receipt changes over STOMP, through real WebSocket connections to the running server, against
 * PostgreSQL in a container: a change is stored before it is delivered, delivered only to its conversation's current
 * members, never delivered if it couldn't be stored, and acknowledgements that change nothing deliver nothing.
 * <p>
 * Like {@link StompMessagingIT}, the tests show that something did <em>not</em> arrive with a marker sent afterwards,
 * never by sleeping. Acknowledgements go through REST, which returns once the change has been published to every
 * connection's outbound queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StompReceiptsIT {

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

	@Test
	void aChangeIsStoredAndThenDeliveredToTheConversationsMembersTheMemberIncluded() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = conversationService.createDirectConversation(alice.id(), bob.id()).conversation().id();
		UUID message = restSend(alice, direct, "Did you see this?");

		try (StompTestClient aliceClient = subscribedToReceipts(alice, direct);
				StompTestClient bobClient = subscribedToReceipts(bob, direct)) {
			MvcTestResult read = restReceipt(bob, direct, "read", message);

			Frame toAlice = aliceClient.nextMessage("receipts");
			Frame toBob = bobClient.nextMessage("receipts");

			assertThat(toAlice.header("destination")).isEqualTo(receiptsTopic(direct));
			assertThat(toAlice.header("content-type")).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
			// The sender learns that Bob read it; Bob's other connections learn it too.
			JsonNode position = json(toAlice);
			assertThat(position.get("userId").asString()).isEqualTo(bob.id().toString());
			assertThat(position.get("lastReadSequenceNumber").asLong()).isEqualTo(1);
			assertThat(json(toBob)).isEqualTo(position);
			// The same JSON as the REST response, and already stored.
			assertThat(position).isEqualTo(jsonMapper.readTree(body(read)));
			assertThat(jdbcTemplate.queryForObject("""
					select last_read_sequence_number from receipt_positions where conversation_id = ? and user_id = ?
					""", Long.class, direct, bob.id())).isEqualTo(1);
			assertThat(toAlice.body()).doesNotContain("@example.com", "Did you see this?");
		}
	}

	@Test
	void acknowledgementsThatChangeNothingDeliverNothing() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID first = restSend(alice, group, "One");
		UUID second = restSend(alice, group, "Two");
		UUID third = restSend(alice, group, "Three");

		try (StompTestClient aliceClient = subscribedToReceipts(alice, group)) {
			restReceipt(bob, group, "delivered", second);
			assertThat(json(aliceClient.nextMessage("receipts")).get("lastDeliveredSequenceNumber").asLong())
					.isEqualTo(2);

			// A repeated and an outdated acknowledgement: retries, duplicates, requests that arrive late.
			restReceipt(bob, group, "delivered", second);
			restReceipt(bob, group, "delivered", first);
			// The marker: the next change.
			restReceipt(bob, group, "read", third);

			JsonNode next = json(aliceClient.nextMessage("receipts"));
			assertThat(next.get("lastReadSequenceNumber").asLong()).isEqualTo(3);
			assertThat(next.get("lastDeliveredSequenceNumber").asLong()).isEqualTo(3);
		}
	}

	@Test
	void changesAreDeliveredOnlyToTheirOwnConversationsReceiptsTopic() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID first = createGroup(alice, bob);
		UUID second = createGroup(alice, bob, carol);
		UUID inFirst = restSend(alice, first, "In the first");
		UUID inSecond = restSend(alice, second, "In the second");

		try (StompTestClient aliceClient = StompTestClient.connected(port, alice.accessToken());
				StompTestClient carolClient = subscribedToReceipts(carol, second)) {
			aliceClient.subscribe("first-messages", messagesTopic(first));
			aliceClient.subscribe("first", receiptsTopic(first));
			aliceClient.subscribe("second", receiptsTopic(second));
			aliceClient.sync();

			restReceipt(bob, first, "read", inFirst);
			restReceipt(bob, second, "read", inSecond);

			// Not on the messages topic, and not on the other conversation's receipts topic.
			Frame toFirst = aliceClient.nextMessage("first");
			assertThat(json(toFirst).get("conversationId").asString()).isEqualTo(first.toString());
			assertThat(json(aliceClient.nextMessage("second")).get("conversationId").asString())
					.isEqualTo(second.toString());
			// Carol, who is only in the second group, gets nothing from the first.
			assertThat(json(carolClient.nextMessage("receipts")).get("conversationId").asString())
					.isEqualTo(second.toString());
		}
	}

	@Test
	void aRemovedMemberStopsReceivingTheConversationsReceiptChanges() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		UUID direct = conversationService.createDirectConversation(alice.id(), carol.id()).conversation().id();
		UUID inGroup = restSend(alice, group, "In the group");
		UUID inDirect = restSend(carol, direct, "In the direct conversation");

		try (StompTestClient carolClient = StompTestClient.connected(port, carol.accessToken())) {
			carolClient.subscribe("group", receiptsTopic(group));
			carolClient.subscribe("direct", receiptsTopic(direct));
			carolClient.sync();

			// Carol's subscription stays, but she isn't a recipient any more.
			conversationService.removeMember(alice.id(), group, carol.id());
			restReceipt(bob, group, "read", inGroup);
			restReceipt(alice, direct, "read", inDirect);

			assertThat(json(carolClient.nextMessage("direct")).get("userId").asString())
					.isEqualTo(alice.id().toString());

			// Nor can she subscribe again.
			carolClient.subscribe("again", receiptsTopic(group));
			Frame error = carolClient.next();
			assertThat(error.command()).isEqualTo(StompCommand.ERROR);
			assertThat(error.header("message")).isEqualTo("Conversation not found.");
		}
	}

	@Test
	void onlyMembersCanSubscribeToAConversationsReceipts() {
		TestUser alice = createUser("Alice");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice);

		for (String destination : List.of(receiptsTopic(group), receiptsTopic(UUID.randomUUID()))) {
			Frame error = rejectedSubscription(mallory, destination);
			assertThat(error.header("message")).as(destination).isEqualTo("Conversation not found.");
			assertThat(json(error).get("status").asInt()).isEqualTo(404);
		}
		for (String destination : List.of("/topic/conversations/*/receipts", "/topic/conversations/**",
				"/topic/conversations/" + group.toString().toUpperCase() + "/receipts",
				"/topic/conversations/" + group + "/receipts/extra")) {
			Frame error = rejectedSubscription(alice, destination);
			assertThat(error.header("message")).as(destination).isEqualTo("Unknown destination.");
		}
	}

	@Test
	void aChangeThatCannotBeStoredIsNotDelivered() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID message = restSend(alice, group, "Hello");

		try (StompTestClient aliceClient = subscribedToReceipts(alice, group)) {
			// PostgreSQL rejects the position when its transaction commits, after everything was written.
			jdbcTemplate.execute("""
					create function simulated_receipt_failure() returns trigger language plpgsql as $$
					begin
						raise exception 'simulated failure';
					end
					$$
					""");
			jdbcTemplate.execute("create constraint trigger simulated_receipt_failure after insert or update "
					+ "on receipt_positions deferrable initially deferred for each row "
					+ "when (new.conversation_id = '" + group + "') execute function simulated_receipt_failure()");
			try {
				MvcTestResult failed = restReceipt(bob, group, "read", message, HttpStatus.INTERNAL_SERVER_ERROR);
				assertThat(body(failed)).doesNotContainIgnoringCase("simulated").doesNotContainIgnoringCase("sql");
			}
			finally {
				jdbcTemplate.execute("drop trigger simulated_receipt_failure on receipt_positions");
				jdbcTemplate.execute("drop function simulated_receipt_failure()");
			}

			// Nobody received it: the next thing Alice gets is the marker, which is the same change, now stored.
			MvcTestResult stored = restReceipt(bob, group, "read", message);
			assertThat(json(aliceClient.nextMessage("receipts"))).isEqualTo(jsonMapper.readTree(body(stored)));
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

	/** A connection subscribed to the conversation's receipt changes as {@code "receipts"}, ready to receive. */
	private StompTestClient subscribedToReceipts(TestUser user, UUID conversationId) {
		StompTestClient client = StompTestClient.connected(port, user.accessToken());
		client.subscribe("receipts", receiptsTopic(conversationId));
		client.sync();
		return client;
	}

	/** Subscribes on a new connection and returns the ERROR frame, after which the server closed the connection. */
	private Frame rejectedSubscription(TestUser user, String destination) {
		try (StompTestClient client = StompTestClient.connected(port, user.accessToken())) {
			client.subscribe("rejected", destination);
			Frame error = client.next();
			assertThat(error.command()).as("frame: %s", error).isEqualTo(StompCommand.ERROR);
			client.awaitClosed();
			return error;
		}
	}

	private static String receiptsTopic(UUID conversationId) {
		return "/topic/conversations/" + conversationId + "/receipts";
	}

	private static String messagesTopic(UUID conversationId) {
		return "/topic/conversations/" + conversationId + "/messages";
	}

	/** Sends a message through REST and returns its ID. */
	private UUID restSend(TestUser caller, UUID conversationId, String content) {
		MvcTestResult result = mvc.post().uri("/api/v1/conversations/{id}/messages", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(new SendMessageRequest(content)))
				.exchange();
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return UUID.fromString(jsonMapper.readTree(body(result)).get("id").asString());
	}

	/**
	 * Acknowledges through REST ({@code "delivered"} or {@code "read"}). Returns once the change is stored and has
	 * been published to every connection's outbound queue, so whatever a connection receives next was published after
	 * it.
	 */
	private MvcTestResult restReceipt(TestUser caller, UUID conversationId, String receipt, UUID messageId) {
		return restReceipt(caller, conversationId, receipt, messageId, HttpStatus.OK);
	}

	private MvcTestResult restReceipt(TestUser caller, UUID conversationId, String receipt, UUID messageId,
			HttpStatus expected) {
		MvcTestResult result = mvc.post().uri("/api/v1/conversations/{id}/receipts/" + receipt, conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\": \"" + messageId + "\"}")
				.exchange();
		assertThat(result).hasStatus(expected);
		return result;
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

}
