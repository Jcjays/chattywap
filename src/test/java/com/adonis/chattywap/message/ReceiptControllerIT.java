package com.adonis.chattywap.message;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.SendMessageRequest;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery and read receipts and unread counts through HTTP, with access tokens validated by the real security
 * configuration, against PostgreSQL in a container. Tests share one database, so each test creates its own users and
 * conversations. Messages are sent through the message endpoint, so they get their sequence numbers the way the
 * application assigns them.
 * <p>
 * Concurrent acknowledgements and transaction failures are in {@link ReceiptServiceIT}; real-time delivery of receipt
 * changes in {@code StompReceiptsIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReceiptControllerIT {

	private static final String CONVERSATIONS = "/api/v1/conversations";

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
	// Delivery

	@Test
	void aRecipientCanAcknowledgeDelivery() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		send(alice, group, "One");
		UUID second = send(alice, group, "Two");

		MvcTestResult result = delivered(bob, group, second);

		assertThat(result).hasStatusOk();
		JsonNode position = json(result);
		assertThat(position.get("conversationId").asString()).isEqualTo(group.toString());
		assertThat(position.get("userId").asString()).isEqualTo(bob.id().toString());
		assertThat(position.get("lastDeliveredSequenceNumber").asLong()).isEqualTo(2);
		assertThat(position.get("deliveredAt").isNull()).isFalse();
		// Delivered isn't read.
		assertThat(position.get("lastReadSequenceNumber").asLong()).isZero();
		assertThat(position.get("readAt").isNull()).isTrue();
		assertThat(unreadCount(bob, group)).isEqualTo(2);

		// Stored before the response was sent, as the response shows it.
		assertThat(storedPosition(group, bob)).containsEntry("last_delivered_sequence_number", 2L)
				.containsEntry("last_read_sequence_number", null);
		assertThat(storedTime("delivered_at", group, bob))
				.isEqualTo(Instant.parse(position.get("deliveredAt").asString()));
		// Nobody else's position changed.
		assertThat(storedPositionCount(group)).isEqualTo(1);
	}

	@Test
	void anAcknowledgementCoversEveryEarlierMessageTheMembersOwnIncluded() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = createDirect(alice, bob);
		send(alice, direct, "One");
		send(bob, direct, "Two");
		UUID third = send(alice, direct, "Three");
		UUID bobsLast = send(bob, direct, "Four");

		assertThat(json(delivered(bob, direct, third)).get("lastDeliveredSequenceNumber").asLong()).isEqualTo(3);
		assertThat(json(delivered(bob, direct, bobsLast)).get("lastDeliveredSequenceNumber").asLong()).isEqualTo(4);
	}

	@Test
	void repeatingADeliveryAcknowledgementChangesNothing() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID message = send(alice, group, "Hello");

		MvcTestResult first = delivered(bob, group, message);
		MvcTestResult again = delivered(bob, group, message);

		assertThat(again).hasStatusOk();
		// Identical, including the time: the position didn't move again.
		assertThat(json(again)).isEqualTo(json(first));
		assertThat(storedPositionCount(group)).isEqualTo(1);
	}

	@Test
	void anOutdatedDeliveryAcknowledgementDoesNotMoveThePositionBack() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID first = send(alice, group, "One");
		send(alice, group, "Two");
		UUID third = send(alice, group, "Three");
		JsonNode atThird = json(delivered(bob, group, third));

		// For example a retry that arrives after a later acknowledgement.
		MvcTestResult outdated = delivered(bob, group, first);

		assertThat(outdated).hasStatusOk();
		assertThat(json(outdated)).isEqualTo(atThird);
		assertThat(storedPosition(group, bob)).containsEntry("last_delivered_sequence_number", 3L);
	}

	@Test
	void nonMembersCannotAcknowledgeDelivery() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, bob);
		UUID direct = createDirect(alice, bob);
		UUID inGroup = send(alice, group, "Secret plans");
		UUID inDirect = send(alice, direct, "Secret plans");

		MvcTestResult groupResult = delivered(mallory, group, inGroup);
		MvcTestResult directResult = delivered(mallory, direct, inDirect);
		MvcTestResult unknownResult = delivered(mallory, UUID.randomUUID(), inGroup);

		assertConversationNotFound(groupResult, directResult, unknownResult);
		// The same answer as for an unknown conversation: nothing about the message or its conversation.
		assertThat(withoutInstance(groupResult)).isEqualTo(withoutInstance(unknownResult));
		assertThat(storedPositionCount(group)).isZero();
		assertThat(storedPositionCount(direct)).isZero();
	}

	@Test
	void formerMembersAndMembersOfDeletedGroupsCannotAcknowledge() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		UUID message = send(alice, group, "Hello");
		UUID deletedGroup = createGroup(alice, bob);
		UUID inDeleted = send(alice, deletedGroup, "Hello");
		conversationService.leave(bob.id(), group);
		conversationService.removeMember(alice.id(), group, carol.id());
		conversationService.deleteGroup(alice.id(), deletedGroup);

		assertConversationNotFound(delivered(bob, group, message), read(bob, group, message),
				delivered(carol, group, message), read(carol, group, message),
				delivered(alice, deletedGroup, inDeleted), read(bob, deletedGroup, inDeleted));
	}

	@Test
	void aMessageOfAnotherConversationCannotBeAcknowledgedThroughThisOne() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID otherGroup = createGroup(alice, bob);
		send(alice, group, "In the group");
		UUID inOther = send(alice, otherGroup, "In the other group");

		// Bob is a member of both, but the message isn't the group's.
		MvcTestResult wrongConversation = delivered(bob, group, inOther);
		MvcTestResult wrongConversationRead = read(bob, group, inOther);
		MvcTestResult unknownMessage = delivered(bob, group, UUID.randomUUID());

		assertMessageNotFound(wrongConversation, wrongConversationRead, unknownMessage);
		// A message elsewhere looks exactly like one that doesn't exist.
		assertThat(withoutInstance(wrongConversation)).isEqualTo(withoutInstance(unknownMessage));
		assertThat(storedPositionCount(group)).isZero();
		assertThat(storedPositionCount(otherGroup)).isZero();
	}

	@Test
	void theMemberWhoAcknowledgesIsAlwaysTheCaller() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID message = send(bob, group, "Hello");

		// Attempts to acknowledge for Bob, in the query string and in the body, are ignored: it's Alice's position.
		for (String receipt : List.of("delivered", "read")) {
			MvcTestResult result = mvc.post()
					.uri(receipts(group) + "/" + receipt + "?userId={id}&memberId={id}", bob.id(), bob.id())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"messageId\": \"" + message + "\", \"userId\": \"" + bob.id() + "\"}")
					.exchange();

			assertThat(result).hasStatusOk();
			assertThat(json(result).get("userId").asString()).isEqualTo(alice.id().toString());
		}
		assertThat(count("select count(*) from receipt_positions where conversation_id = ? and user_id = ?", group,
				bob.id())).isZero();
		assertThat(storedPosition(group, alice)).containsEntry("last_read_sequence_number", 1L);
	}

	@Test
	void invalidAcknowledgementsAreBadRequests() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		send(alice, group, "Hello");

		for (String receipt : List.of("delivered", "read")) {
			String uri = receipts(group) + "/" + receipt;
			MvcTestResult missing = postJson(alice, uri, "{}");
			MvcTestResult nullId = postJson(alice, uri, "{\"messageId\": null}");
			MvcTestResult malformedId = postJson(alice, uri, "{\"messageId\": \"not-a-uuid\"}");
			MvcTestResult malformedJson = postJson(alice, uri, "{\"messageId\":");
			MvcTestResult malformedConversation = postJson(alice,
					CONVERSATIONS + "/not-a-uuid/receipts/" + receipt, "{\"messageId\": \"" + UUID.randomUUID() + "\"}");

			for (MvcTestResult result : List.of(missing, nullId)) {
				assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
				assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Request validation failed.");
				assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("messageId");
			}
			for (MvcTestResult result : List.of(malformedId, malformedJson, malformedConversation)) {
				assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
				assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
				assertThat(body(result)).doesNotContainIgnoringCase("exception");
			}
			// Rejected values in the body are never echoed.
			assertThat(body(malformedId)).doesNotContain("not-a-uuid");
		}
		assertThat(storedPositionCount(group)).isZero();
	}

	@Test
	void receiptEndpointsNeedAnAccessToken() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		UUID message = send(alice, group, "Hello");
		String body = "{\"messageId\": \"" + message + "\"}";

		for (String receipt : List.of("delivered", "read")) {
			assertUnauthorized(mvc.post().uri(receipts(group) + "/" + receipt)
					.contentType(MediaType.APPLICATION_JSON).content(body).exchange());
			assertUnauthorized(mvc.post().uri(receipts(group) + "/" + receipt)
					.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
					.contentType(MediaType.APPLICATION_JSON).content(body).exchange());
		}
		assertUnauthorized(mvc.get().uri(receipts(group)).exchange());
		assertUnauthorized(mvc.get().uri(unreadCountUri(group)).exchange());
		assertThat(storedPositionCount(group)).isZero();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Read receipts

	@Test
	void aMemberCanAdvanceTheirReadPosition() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = createDirect(alice, bob);
		send(alice, direct, "One");
		UUID second = send(alice, direct, "Two");
		UUID third = send(alice, direct, "Three");

		MvcTestResult atSecond = read(bob, direct, second);
		MvcTestResult atThird = read(bob, direct, third);

		assertThat(atSecond).hasStatusOk();
		assertThat(json(atSecond).get("lastReadSequenceNumber").asLong()).isEqualTo(2);
		JsonNode position = json(atThird);
		assertThat(position.get("lastReadSequenceNumber").asLong()).isEqualTo(3);
		assertThat(Instant.parse(position.get("readAt").asString()))
				.isAfterOrEqualTo(Instant.parse(json(atSecond).get("readAt").asString()))
				.isEqualTo(storedTime("read_at", direct, bob));
		// Reading acknowledges delivery up to the same message.
		assertThat(position.get("lastDeliveredSequenceNumber").asLong()).isEqualTo(3);
		assertThat(position.get("deliveredAt").asString()).isEqualTo(position.get("readAt").asString());

		// In a direct conversation, the sender learns that the other participant has read their messages.
		assertThat(receiptsOf(alice, direct)).containsEntry(bob.id(), position);
	}

	@Test
	void repeatingAReadUpdateIsSafe() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID message = send(alice, group, "Hello");

		JsonNode first = json(read(bob, group, message));
		MvcTestResult again = read(bob, group, message);

		assertThat(again).hasStatusOk();
		assertThat(json(again)).isEqualTo(first);
		assertThat(unreadCount(bob, group)).isZero();
	}

	@Test
	void anOutdatedReadUpdateDoesNotMoveThePositionBack() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID first = send(alice, group, "One");
		send(alice, group, "Two");
		UUID third = send(alice, group, "Three");
		JsonNode atThird = json(read(bob, group, third));

		MvcTestResult outdated = read(bob, group, first);

		assertThat(outdated).hasStatusOk();
		assertThat(json(outdated)).isEqualTo(atThird);
		assertThat(unreadCount(bob, group)).isZero();
	}

	@Test
	void readingDoesNotMoveADeliveredPositionThatIsAheadBack() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID first = send(alice, group, "One");
		UUID second = send(alice, group, "Two");
		UUID third = send(alice, group, "Three");
		JsonNode delivered = json(delivered(bob, group, third));

		JsonNode afterRead = json(read(bob, group, first));

		assertThat(afterRead.get("lastReadSequenceNumber").asLong()).isEqualTo(1);
		assertThat(afterRead.get("lastDeliveredSequenceNumber").asLong()).isEqualTo(3);
		assertThat(afterRead.get("deliveredAt")).isEqualTo(delivered.get("deliveredAt"));
		assertThat(unreadCount(bob, group)).isEqualTo(2);

		// And delivery acknowledgements never move the read position.
		delivered(bob, group, third);
		assertThat(json(read(bob, group, second)).get("lastReadSequenceNumber").asLong()).isEqualTo(2);
		assertThat(storedPosition(group, bob)).containsEntry("last_read_sequence_number", 2L)
				.containsEntry("last_delivered_sequence_number", 3L);
	}

	@Test
	void groupMembersReadPositionsAreIndependent() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		TestUser dave = createUser("Dave");
		UUID group = createGroup(alice, bob, carol, dave);
		UUID first = send(alice, group, "One");
		send(alice, group, "Two");
		UUID third = send(alice, group, "Three");

		read(bob, group, third);
		read(carol, group, first);

		Map<UUID, JsonNode> receipts = receiptsOf(alice, group);
		assertThat(receipts.get(bob.id()).get("lastReadSequenceNumber").asLong()).isEqualTo(3);
		assertThat(receipts.get(carol.id()).get("lastReadSequenceNumber").asLong()).isEqualTo(1);
		// One member's acknowledgement says nothing about the others.
		assertThat(receipts.get(dave.id()).get("lastReadSequenceNumber").asLong()).isZero();
		assertThat(receipts.get(dave.id()).get("lastDeliveredSequenceNumber").asLong()).isZero();
		assertThat(unreadCount(bob, group)).isZero();
		assertThat(unreadCount(carol, group)).isEqualTo(2);
		assertThat(unreadCount(dave, group)).isEqualTo(3);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Listing receipts

	@Test
	void membersSeeEveryCurrentMembersPositions() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		UUID first = send(alice, group, "One");
		UUID second = send(alice, group, "Two");
		read(bob, group, second);
		delivered(carol, group, first);

		MvcTestResult result = get(carol, receipts(group));

		assertThat(result).hasStatusOk();
		List<JsonNode> members = new ArrayList<>();
		json(result).get("members").forEach(members::add);
		// Every member once, ordered by ID as text, the caller included.
		assertThat(members).extracting(member -> member.get("userId").asString())
				.containsExactlyElementsOf(sortedIds(alice, bob, carol));
		Map<UUID, JsonNode> byUser = receiptsOf(carol, group);
		assertThat(byUser.get(bob.id())).isEqualTo(json(read(bob, group, second)));
		assertThat(byUser.get(carol.id()).get("lastDeliveredSequenceNumber").asLong()).isEqualTo(1);
		// Alice has acknowledged nothing.
		JsonNode none = byUser.get(alice.id());
		assertThat(none.get("conversationId").asString()).isEqualTo(group.toString());
		assertThat(none.get("lastDeliveredSequenceNumber").asLong()).isZero();
		assertThat(none.get("deliveredAt").isNull()).isTrue();
		assertThat(none.get("lastReadSequenceNumber").asLong()).isZero();
		assertThat(none.get("readAt").isNull()).isTrue();
		// Nothing but IDs, sequence numbers and times.
		assertThat(body(result)).doesNotContain("@example.com", "Alice");
	}

	@Test
	void nonMembersCannotSeeReceiptsOrUnreadCounts() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, bob);
		UUID message = send(alice, group, "Hello");
		read(bob, group, message);
		conversationService.leave(bob.id(), group);

		assertConversationNotFound(get(mallory, receipts(group)), get(mallory, unreadCountUri(group)),
				get(mallory, receipts(UUID.randomUUID())), get(mallory, unreadCountUri(UUID.randomUUID())),
				get(bob, receipts(group)), get(bob, unreadCountUri(group)));
	}

	@Test
	void aMemberWhoLeavesTakesTheirPositionWithThemAndStartsAgainWithout() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		send(alice, group, "One");
		UUID second = send(alice, group, "Two");
		read(bob, group, second);

		conversationService.leave(bob.id(), group);

		// The position belonged to the membership.
		assertThat(storedPositionCount(group)).isZero();
		assertThat(receiptsOf(alice, group)).containsOnlyKeys(alice.id());

		// A new membership has no position: everything others sent is unread again.
		conversationService.addMembers(alice.id(), group, List.of(bob.id()));
		assertThat(receiptsOf(bob, group).get(bob.id()).get("lastReadSequenceNumber").asLong()).isZero();
		assertThat(unreadCount(bob, group)).isEqualTo(2);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Unread counts

	@Test
	void aMembersOwnMessagesAreNeverUnread() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		send(alice, group, "One");
		send(alice, group, "Two");
		send(alice, group, "Three");
		send(bob, group, "Four");
		send(bob, group, "Five");

		assertThat(unreadCount(alice, group)).isEqualTo(2);
		assertThat(unreadCount(bob, group)).isEqualTo(3);
		assertThat(json(get(bob, unreadCountUri(group))).get("conversationId").asString()).isEqualTo(group.toString());
	}

	@Test
	void messagesAfterTheReadPositionAreUnreadAndAdvancingItReducesTheCount() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		List<UUID> messages = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			messages.add(send(alice, group, "Message " + i));
		}
		send(bob, group, "Bob's own");

		read(bob, group, messages.get(1));
		assertThat(unreadCount(bob, group)).isEqualTo(3);

		read(bob, group, messages.get(3));
		assertThat(unreadCount(bob, group)).isEqualTo(1);

		// Reading through his own, newest message covers everything before it.
		UUID bobsNewest = send(bob, group, "Bob again");
		read(bob, group, bobsNewest);
		assertThat(unreadCount(bob, group)).isZero();

		// A new message from someone else is unread again.
		send(alice, group, "New");
		assertThat(unreadCount(bob, group)).isEqualTo(1);
		// Alice reads nothing: Bob's messages are unread for her.
		assertThat(unreadCount(alice, group)).isEqualTo(2);
	}

	@Test
	void withoutAReadPositionEveryMessageFromOthersIsUnread() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		UUID empty = createGroup(alice, bob);
		send(alice, group, "One");
		send(carol, group, "Two");
		UUID third = send(alice, group, "Three");
		send(bob, group, "Four");

		assertThat(unreadCount(bob, group)).isEqualTo(3);
		assertThat(unreadCount(bob, empty)).isZero();
		// A delivery acknowledgement isn't a read position.
		delivered(bob, group, third);
		assertThat(unreadCount(bob, group)).isEqualTo(3);
	}

	@Test
	void theConversationListShowsTheSameUnreadCountsAsEachConversation() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		// Bob hasn't read anything in the direct conversation.
		UUID direct = createDirect(alice, bob);
		for (int i = 0; i < 3; i++) {
			send(alice, direct, "Direct " + i);
		}
		// He read half of a group, in which others sent messages after his position too.
		UUID partlyRead = createGroup(alice, bob, carol);
		List<UUID> partlyReadMessages = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			partlyReadMessages.add(send(alice, partlyRead, "Partly read " + i));
		}
		send(carol, partlyRead, "From Carol");
		send(bob, partlyRead, "From Bob");
		read(bob, partlyRead, partlyReadMessages.get(1));
		// He read everything of another one.
		UUID allRead = createGroup(bob, alice);
		send(alice, allRead, "Read");
		read(bob, allRead, send(bob, allRead, "Mine"));
		// He only sent messages to a third, and only acknowledged delivery in a fourth.
		UUID ownOnly = createGroup(bob);
		send(bob, ownOnly, "Note to self");
		UUID deliveredOnly = createGroup(alice, bob);
		send(alice, deliveredOnly, "One");
		delivered(bob, deliveredOnly, send(alice, deliveredOnly, "Two"));
		UUID empty = createGroup(carol, bob);
		// Not his: never counted.
		UUID notBobs = createGroup(alice, carol);
		send(alice, notBobs, "Not for Bob");

		// Partly read: Alice's last two and Carol's, not Bob's own.
		Map<UUID, Long> expected = Map.of(direct, 3L, partlyRead, 3L, allRead, 0L, ownOnly, 0L, deliveredOnly, 2L,
				empty, 0L);
		Map<UUID, Long> individually = new HashMap<>();
		expected.keySet().forEach(id -> individually.put(id, unreadCount(bob, id)));
		assertThat(individually).isEqualTo(expected);

		assertThat(listUnreadCounts(bob, CONVERSATIONS + "?size=100")).isEqualTo(expected);
		// The same on pages by cursor, with read positions and without on the same page and across pages.
		Map<UUID, Long> byCursor = new HashMap<>();
		String cursor = "";
		do {
			MvcTestResult page = get(bob, CONVERSATIONS + "?size=2&cursor=" + cursor);
			byCursor.putAll(unreadCounts(page));
			JsonNode next = json(page).get("nextCursor");
			cursor = next.isNull() ? null : next.asString();
		} while (cursor != null);
		assertThat(byCursor).isEqualTo(expected);
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

	private UUID createDirect(TestUser caller, TestUser recipient) {
		return conversationService.createDirectConversation(caller.id(), recipient.id()).conversation().id();
	}

	/** Sends through the message endpoint and returns the stored message's ID. */
	private UUID send(TestUser caller, UUID conversationId, String content) {
		MvcTestResult result = postJson(caller, CONVERSATIONS + "/" + conversationId + "/messages",
				jsonMapper.writeValueAsString(new SendMessageRequest(content)));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return UUID.fromString(json(result).get("id").asString());
	}

	private static String receipts(UUID conversationId) {
		return CONVERSATIONS + "/" + conversationId + "/receipts";
	}

	private static String unreadCountUri(UUID conversationId) {
		return CONVERSATIONS + "/" + conversationId + "/unread-count";
	}

	private MvcTestResult delivered(TestUser caller, UUID conversationId, UUID messageId) {
		return postJson(caller, receipts(conversationId) + "/delivered", "{\"messageId\": \"" + messageId + "\"}");
	}

	private MvcTestResult read(TestUser caller, UUID conversationId, UUID messageId) {
		return postJson(caller, receipts(conversationId) + "/read", "{\"messageId\": \"" + messageId + "\"}");
	}

	private long unreadCount(TestUser caller, UUID conversationId) {
		MvcTestResult result = get(caller, unreadCountUri(conversationId));
		assertThat(result).hasStatusOk();
		return json(result).get("unreadCount").asLong();
	}

	/** The conversation's receipts as the caller sees them, by member. */
	private Map<UUID, JsonNode> receiptsOf(TestUser caller, UUID conversationId) {
		MvcTestResult result = get(caller, receipts(conversationId));
		assertThat(result).hasStatusOk();
		Map<UUID, JsonNode> byUser = new HashMap<>();
		json(result).get("members")
				.forEach(member -> byUser.put(UUID.fromString(member.get("userId").asString()), member));
		return byUser;
	}

	/** The unread counts of the conversation list's first page, by conversation. */
	private Map<UUID, Long> listUnreadCounts(TestUser caller, String uri) {
		return unreadCounts(get(caller, uri));
	}

	private Map<UUID, Long> unreadCounts(MvcTestResult page) {
		assertThat(page).hasStatusOk();
		Map<UUID, Long> counts = new HashMap<>();
		json(page).get("content").forEach(entry -> counts.put(UUID.fromString(entry.get("id").asString()),
				entry.get("unreadCount").asLong()));
		return counts;
	}

	private static List<String> sortedIds(TestUser... users) {
		return Arrays.stream(users).map(user -> user.id().toString()).sorted(Comparator.naturalOrder()).toList();
	}

	private MvcTestResult get(TestUser caller, String uri) {
		return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()).exchange();
	}

	private MvcTestResult postJson(TestUser caller, String uri, String json) {
		return mvc.post().uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json)
				.exchange();
	}

	private Map<String, Object> storedPosition(UUID conversationId, TestUser user) {
		return jdbcTemplate.queryForMap("""
				select last_delivered_sequence_number, last_read_sequence_number
				from receipt_positions where conversation_id = ? and user_id = ?
				""", conversationId, user.id());
	}

	private Instant storedTime(String column, UUID conversationId, TestUser user) {
		return jdbcTemplate.queryForObject("select " + column + " from receipt_positions "
				+ "where conversation_id = ? and user_id = ?", OffsetDateTime.class, conversationId, user.id())
				.toInstant();
	}

	private int storedPositionCount(UUID conversationId) {
		return count("select count(*) from receipt_positions where conversation_id = ?", conversationId);
	}

	private static void assertUnauthorized(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	/** The same 404 as for a conversation that doesn't exist. */
	private static void assertConversationNotFound(MvcTestResult... results) {
		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
		}
	}

	private static void assertMessageNotFound(MvcTestResult... results) {
		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Message not found.");
		}
	}

	/** The problem detail without its {@code instance}, which repeats the request path. */
	private String withoutInstance(MvcTestResult result) {
		return body(result).replace(result.getRequest().getRequestURI(), "");
	}

	private JsonNode json(MvcTestResult result) {
		return jsonMapper.readTree(body(result));
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

}
