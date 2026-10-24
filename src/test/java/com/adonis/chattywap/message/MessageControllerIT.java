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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The message endpoints through HTTP, with access tokens validated by the real security configuration, against
 * PostgreSQL in a container. Tests share one database, so each test creates its own users and conversations.
 * <p>
 * Users are created through {@link UserService} and get tokens from the application's {@link JwtEncoder}. The
 * conversations are set up through {@link ConversationService}; their own endpoints have their own tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MessageControllerIT {

	private static final String INVALID_CHARACTERS = "must not contain NUL characters or unpaired surrogates";

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
	// Sending

	@Test
	void membersCanSendAMessage() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		MvcTestResult result = send(bob, group, "Hello, everyone!");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.conversationId").isEqualTo(group.toString());
		assertThat(result).bodyJson().extractingPath("$.sender.id").isEqualTo(bob.id().toString());
		assertThat(result).bodyJson().extractingPath("$.sender.displayName").isEqualTo("Bob");
		assertThat(result).bodyJson().extractingPath("$.content").isEqualTo("Hello, everyone!");
		// The sender's email address is never shown.
		assertThat(body(result)).doesNotContain("@example.com");

		// Stored before the response was sent, with the values the response shows.
		UUID id = id(result);
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"select conversation_id, sender_id, content from messages where id = ?", id);
		assertThat(row).containsEntry("conversation_id", group)
				.containsEntry("sender_id", bob.id())
				.containsEntry("content", "Hello, everyone!");
		assertThat(jdbcTemplate.queryForObject("select created_at from messages where id = ?", OffsetDateTime.class, id)
				.toInstant()).isEqualTo(Instant.parse(json(result).get("createdAt").asString()));
	}

	@Test
	void bothMembersOfADirectConversationCanSendMessages() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = createDirect(alice, bob);

		assertThat(send(alice, direct, "Hi Bob")).hasStatus(HttpStatus.CREATED);
		assertThat(send(bob, direct, "Hi Alice")).hasStatus(HttpStatus.CREATED);

		assertThat(messageCount(direct)).isEqualTo(2);
	}

	@Test
	void theSenderIsAlwaysTheTokensUser() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		// Attempts to name another user as sender, in the query string and in the body, are ignored.
		MvcTestResult result = mvc.post().uri(messages(group) + "?senderId={id}&userId={id}", bob.id(), bob.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\": \"From Alice\", \"senderId\": \"" + bob.id() + "\", \"sender\": {\"id\": \""
						+ bob.id() + "\"}}")
				.exchange();

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.sender.id").isEqualTo(alice.id().toString());
		assertThat(count("select count(*) from messages where conversation_id = ? and sender_id = ?", group,
				alice.id())).isEqualTo(1);
		assertThat(count("select count(*) from messages where sender_id = ?", bob.id())).isZero();
	}

	@Test
	void contentIsStoredExactlyAsSent() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		String content = "  Line one,\n\tline two  😀 ";

		MvcTestResult result = send(alice, group, content);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.content").isEqualTo(content);
		assertThat(jdbcTemplate.queryForObject("select content from messages where conversation_id = ?", String.class,
				group)).isEqualTo(content);
	}

	@Test
	void blankContentIsRejected() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		List<String> bodies = List.of("{}", "{\"content\": null}", "{\"content\": \"\"}", "{\"content\": \"   \"}",
				"{\"content\": \" \\n\\t\\r \"}");
		for (String json : bodies) {
			MvcTestResult result = postJson(alice, messages(group), json);

			assertThat(result).as(json).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).as(json).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).as(json).bodyJson().extractingPath("$.errors[*].field").asArray()
					.containsOnly("content");
		}
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void contentHasAMaximumLength() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		int max = SendMessageRequest.MAX_CONTENT_LENGTH;
		// U+1F600 (an emoji) is one character but two UTF-16 code units, and the limit counts code units.
		String emoji = "😀";

		assertThat(send(alice, group, "a".repeat(max))).hasStatus(HttpStatus.CREATED);
		assertThat(send(alice, group, emoji.repeat(max / 2))).hasStatus(HttpStatus.CREATED);
		MvcTestResult tooLong = send(alice, group, "a".repeat(max + 1));
		MvcTestResult tooManyEmoji = send(alice, group, emoji.repeat(max / 2 + 1));

		for (MvcTestResult result : List.of(tooLong, tooManyEmoji)) {
			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("content");
			// Rejected values are never echoed.
			assertThat(body(result)).doesNotContain("aaaa", emoji);
		}
		assertThat(messageCount(group)).isEqualTo(2);
	}

	@Test
	void contentWithANulCharacterIsRejected() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		// PostgreSQL can't store U+0000 in text. Without the check, this would fail in the database with a 500.
		MvcTestResult result = postJson(alice, messages(group), "{\"content\": \"before\\u0000after\"}");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[*].message").asArray()
				.containsExactly(INVALID_CHARACTERS);
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void contentWithHalfASurrogatePairIsRejected() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		// Half of an emoji isn't valid Unicode text. Without the check, PostgreSQL would store "?" in its place, and
		// the history would differ from the response to sending it.
		for (String json : List.of("{\"content\": \"broken \\uD83D emoji\"}", "{\"content\": \"\\uDE00\"}")) {
			MvcTestResult result = postJson(alice, messages(group), json);

			assertThat(result).as(json).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).as(json).bodyJson().extractingPath("$.errors[*].message").asArray()
					.containsExactly(INVALID_CHARACTERS);
		}
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void malformedBodiesAreBadRequests() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		MvcTestResult notJson = postJson(alice, messages(group), "{\"content\": ");
		MvcTestResult noBody = mvc.post().uri(messages(group))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.exchange();
		MvcTestResult notAString = postJson(alice, messages(group), "{\"content\": {\"text\": \"Hi\"}}");

		for (MvcTestResult result : List.of(notJson, noBody, notAString)) {
			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		}
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void aMessageThatCannotBeStoredIsAServerErrorWithoutDetails() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		// The insert runs when the transaction commits, before the response is written.
		MvcTestResult result = whileStoringMessagesFails(group, () -> send(alice, group, "Hello"));

		assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred.");
		assertThat(body(result)).doesNotContainIgnoringCase("simulated").doesNotContainIgnoringCase("sql")
				.doesNotContainIgnoringCase("exception");
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void nonMembersCannotSendMessages() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, bob);
		UUID direct = createDirect(alice, bob);

		MvcTestResult groupResult = send(mallory, group, "Let me in");
		MvcTestResult directResult = send(mallory, direct, "Let me in");
		MvcTestResult unknownResult = send(mallory, UUID.randomUUID(), "Let me in");

		assertNotFound(groupResult, directResult, unknownResult);
		assertThat(withoutInstance(groupResult)).isEqualTo(withoutInstance(unknownResult));
		assertThat(count("select count(*) from messages where sender_id = ?", mallory.id())).isZero();
	}

	@Test
	void formerMembersCannotSendMessages() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		conversationService.leave(bob.id(), group);
		conversationService.removeMember(alice.id(), group, carol.id());

		assertNotFound(send(bob, group, "Still here?"), send(carol, group, "Still here?"));
		assertThat(messageCount(group)).isZero();
	}

	@Test
	void nobodyCanSendMessagesToADeletedGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		assertThat(send(bob, group, "Before")).hasStatus(HttpStatus.CREATED);
		conversationService.deleteGroup(alice.id(), group);

		MvcTestResult fromAdmin = send(alice, group, "After");
		MvcTestResult fromMember = send(bob, group, "After");

		assertNotFound(fromAdmin, fromMember);
		assertThat(withoutInstance(fromAdmin)).isEqualTo(withoutInstance(send(alice, UUID.randomUUID(), "After")));
		// The deletion kept the conversation's messages.
		assertThat(messageCount(group)).isEqualTo(1);
	}

	@Test
	void malformedConversationIdIsABadRequest() {
		TestUser alice = createUser("Alice");

		MvcTestResult sent = postJson(alice, "/api/v1/conversations/not-a-uuid/messages", "{\"content\": \"Hi\"}");
		MvcTestResult listed = get(alice, "/api/v1/conversations/not-a-uuid/messages");

		for (MvcTestResult result : List.of(sent, listed)) {
			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		}
	}

	@Test
	void everyMessageEndpointRequiresAnAccessToken() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		assertUnauthorized(mvc.post().uri(messages(group)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\": \"Hi\"}").exchange());
		assertUnauthorized(mvc.get().uri(messages(group)).exchange());
		assertUnauthorized(mvc.post().uri(messages(group)).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"Hi\"}").exchange());
		assertUnauthorized(mvc.get().uri(messages(group)).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
				.exchange());
		assertThat(messageCount(group)).isZero();
	}

	// ---------------------------------------------------------------------------------------------------------
	// History

	@Test
	void membersSeeTheHistoryNewestFirst() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID first = sendAt(alice, group, "First", minutesAgo(3));
		UUID second = sendAt(bob, group, "Second", minutesAgo(2));
		UUID third = sendAt(alice, group, "Third", minutesAgo(1));

		MvcTestResult result = get(bob, messages(group));

		assertThat(result).hasStatusOk();
		assertThat(ids(result)).containsExactly(third.toString(), second.toString(), first.toString());
		assertThat(result).bodyJson().extractingPath("$.content[*].content").asArray()
				.containsExactly("Third", "Second", "First");
		assertThat(result).bodyJson().extractingPath("$.content[*].sender.displayName").asArray()
				.containsExactly("Alice", "Bob", "Alice");
		assertThat(result).bodyJson().extractingPath("$.content[*].conversationId").asArray()
				.containsOnly(group.toString());
		assertThat(result).bodyJson().extractingPath("$.page.size").isEqualTo(20);
		assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
		assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
		assertThat(result).bodyJson().extractingPath("$.page.totalPages").isEqualTo(1);
		assertThat(result).bodyJson().extractingPath("$.nextCursor").isNull();
		assertThat(body(result)).doesNotContain("@example.com");
	}

	@Test
	void aHistoryEntryIsTheSameAsTheResponseToSendingIt() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		JsonNode sent = json(send(alice, group, "Hello"));
		JsonNode listed = json(get(alice, messages(group))).get("content").get(0);

		// Including createdAt, which the server truncates to the microseconds PostgreSQL stores.
		assertThat(listed).isEqualTo(sent);
	}

	@Test
	void anEmptyConversationHasAnEmptyHistory() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);

		MvcTestResult result = get(alice, messages(group));

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(0);
		assertThat(result).bodyJson().extractingPath("$.nextCursor").isNull();
	}

	@Test
	void historyIsPaginatedByPageNumber() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		List<UUID> newestFirst = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			newestFirst.add(sendAt(alice, group, "Message " + i, minutesAgo(i)));
		}

		MvcTestResult first = get(alice, messages(group) + "?page=0&size=2");
		MvcTestResult second = get(alice, messages(group) + "?page=1&size=2");
		MvcTestResult last = get(alice, messages(group) + "?page=2&size=2");
		MvcTestResult beyond = get(alice, messages(group) + "?page=3&size=2");

		assertThat(ids(first)).containsExactly(newestFirst.get(0).toString(), newestFirst.get(1).toString());
		assertThat(first).bodyJson().extractingPath("$.page.size").isEqualTo(2);
		assertThat(first).bodyJson().extractingPath("$.page.totalElements").isEqualTo(5);
		assertThat(first).bodyJson().extractingPath("$.page.totalPages").isEqualTo(3);
		assertThat(ids(second)).containsExactly(newestFirst.get(2).toString(), newestFirst.get(3).toString());
		assertThat(second).bodyJson().extractingPath("$.page.number").isEqualTo(1);
		assertThat(ids(last)).containsExactly(newestFirst.get(4).toString());
		assertThat(last).bodyJson().extractingPath("$.nextCursor").isNull();
		assertThat(beyond).hasStatusOk();
		assertThat(beyond).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(beyond).bodyJson().extractingPath("$.page.totalElements").isEqualTo(5);
	}

	@Test
	void eachPageCarriesACursorToTheOlderMessages() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		List<UUID> newestFirst = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			newestFirst.add(sendAt(alice, group, "Message " + i, minutesAgo(i)));
		}

		MvcTestResult first = get(alice, messages(group) + "?size=2");
		MvcTestResult second = get(alice, messages(group) + "?size=2&cursor=" + nextCursor(first));
		MvcTestResult third = get(alice, messages(group) + "?size=2&cursor=" + nextCursor(second));

		assertThat(ids(first)).containsExactly(newestFirst.get(0).toString(), newestFirst.get(1).toString());
		assertThat(ids(second)).containsExactly(newestFirst.get(2).toString(), newestFirst.get(3).toString());
		// Page numbers don't apply to a page that starts at a cursor.
		assertThat(second).bodyJson().extractingPath("$.page").isNull();
		assertThat(ids(third)).containsExactly(newestFirst.get(4).toString());
		assertThat(nextCursor(third)).isNull();
	}

	@Test
	void anEmptyCursorIsTheFirstPage() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		UUID message = id(send(alice, group, "Hello"));

		MvcTestResult result = get(alice, messages(group) + "?cursor=");

		assertThat(result).hasStatusOk();
		assertThat(ids(result)).containsExactly(message.toString());
		assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
	}

	@Test
	void messagesStoredAtTheSameMomentAreOrderedById() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		OffsetDateTime sameTime = minutesAgo(1);
		List<UUID> sent = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			sent.add(sendAt(i % 2 == 0 ? alice : bob, group, "Message " + i, sameTime));
		}
		UUID newer = sendAt(alice, group, "Newer", minutesAgo(0));
		UUID older = sendAt(alice, group, "Older", minutesAgo(2));
		// PostgreSQL orders UUIDs like their lower-case text, which is how UUID.toString() writes them.
		List<String> expected = new ArrayList<>();
		expected.add(newer.toString());
		sent.stream().map(UUID::toString).sorted(Comparator.reverseOrder()).forEach(expected::add);
		expected.add(older.toString());

		List<String> byCursor = new ArrayList<>();
		String cursor = "";
		do {
			MvcTestResult page = get(alice, messages(group) + "?size=2&cursor=" + cursor);
			byCursor.addAll(ids(page));
			cursor = nextCursor(page);
		} while (cursor != null);
		List<String> byNumber = new ArrayList<>();
		for (int page = 0; page < 4; page++) {
			byNumber.addAll(ids(get(alice, messages(group) + "?size=2&page=" + page)));
		}

		assertThat(byCursor).containsExactlyElementsOf(expected);
		assertThat(byNumber).containsExactlyElementsOf(expected);
		// Repeating a request gives the same page, also when its cursor lies between messages of the same moment.
		String cursorAfterThree = nextCursor(get(alice, messages(group) + "?size=3"));
		assertThat(ids(get(alice, messages(group) + "?size=2&cursor=" + cursorAfterThree)))
				.isEqualTo(ids(get(alice, messages(group) + "?size=2&cursor=" + cursorAfterThree)))
				.containsExactly(expected.get(3), expected.get(4));
	}

	@Test
	void cursorPagesDoNotShiftWhenNewMessagesArrive() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		List<UUID> newestFirst = new ArrayList<>();
		for (int i = 1; i <= 4; i++) {
			newestFirst.add(sendAt(alice, group, "Message " + i, minutesAgo(i)));
		}
		MvcTestResult first = get(bob, messages(group) + "?size=2");
		assertThat(ids(first)).containsExactly(newestFirst.get(0).toString(), newestFirst.get(1).toString());

		// Before the next page, two new messages arrive.
		UUID newA = id(send(alice, group, "New A"));
		UUID newB = id(send(bob, group, "New B"));

		MvcTestResult second = get(bob, messages(group) + "?size=2&cursor=" + nextCursor(first));

		// The cursor continues right after the first page: nothing repeats and nothing is skipped.
		assertThat(ids(second)).containsExactly(newestFirst.get(2).toString(), newestFirst.get(3).toString());
		assertThat(nextCursor(second)).isNull();
		// The new messages are at the start of the history, for the next reload.
		assertThat(ids(get(bob, messages(group) + "?size=2"))).containsExactlyInAnyOrder(newA.toString(),
				newB.toString());
		// Page numbers, by contrast, shifted: page 1 now repeats the first page's messages.
		assertThat(ids(get(bob, messages(group) + "?size=2&page=1")))
				.containsExactly(newestFirst.get(0).toString(), newestFirst.get(1).toString());
	}

	@Test
	void pageSizeIsCappedAndInvalidValuesFallBackToTheDefaults() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		// 102 messages, inserted directly because sending them one request at a time is slow.
		jdbcTemplate.update("""
				insert into messages (id, conversation_id, sender_id, content, created_at)
				select gen_random_uuid(), ?, ?, 'Message ' || n, now() - n * interval '1 second'
				from generate_series(1, 102) n
				""", group, alice.id());

		MvcTestResult tooLarge = get(alice, messages(group) + "?size=1000");
		MvcTestResult tooLargeByCursor = get(alice, messages(group) + "?size=1000&cursor="
				+ nextCursor(get(alice, messages(group) + "?size=1")));
		MvcTestResult invalid = get(alice, messages(group) + "?page=-1&size=0");
		MvcTestResult notNumbers = get(alice, messages(group) + "?page=first&size=many");

		assertThat(tooLarge).hasStatusOk();
		assertThat(tooLarge).bodyJson().extractingPath("$.page.size").isEqualTo(100);
		assertThat(tooLarge).bodyJson().extractingPath("$.content").asArray().hasSize(100);
		assertThat(tooLargeByCursor).bodyJson().extractingPath("$.content").asArray().hasSize(100);
		assertThat(tooLargeByCursor).bodyJson().extractingPath("$.nextCursor").isNotNull();
		for (MvcTestResult result : List.of(invalid, notNumbers)) {
			assertThat(result).hasStatusOk();
			assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
			assertThat(result).bodyJson().extractingPath("$.page.size").isEqualTo(20);
			assertThat(result).bodyJson().extractingPath("$.content").asArray().hasSize(20);
		}
	}

	@Test
	void aPageWhoseOffsetExceedsTheIntegerRangeIsEmpty() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		send(alice, group, "Hello");

		// 200,000,000 pages of 20 messages: an offset of 4 billion.
		MvcTestResult result = get(alice, messages(group) + "?page=200000000&size=20");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(1);
	}

	@Test
	void anInvalidCursorIsABadRequest() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice);
		String notAPosition = Base64.getUrlEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

		for (String cursor : List.of("not-base64!", notAPosition)) {
			MvcTestResult result = get(alice, messages(group) + "?cursor=" + cursor);

			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Invalid cursor.");
		}
	}

	@Test
	void aHistoryContainsOnlyItsOwnConversationsMessages() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID direct = createDirect(alice, bob);
		OffsetDateTime sameTime = minutesAgo(1);
		UUID inGroup = sendAt(alice, group, "In the group", sameTime);
		UUID inDirect = sendAt(alice, direct, "In the direct conversation", sameTime);
		UUID olderInGroup = sendAt(bob, group, "Older, in the group", minutesAgo(2));
		UUID olderInDirect = sendAt(bob, direct, "Older, in the direct conversation", minutesAgo(2));

		assertThat(ids(get(alice, messages(group)))).containsExactly(inGroup.toString(), olderInGroup.toString());
		assertThat(ids(get(bob, messages(direct)))).containsExactly(inDirect.toString(), olderInDirect.toString());
		assertThat(get(alice, messages(group))).bodyJson().extractingPath("$.page.totalElements").isEqualTo(2);
		// A cursor is only a position. Taken from one conversation's history, it still reads only the history
		// it is used with.
		String groupCursor = nextCursor(get(alice, messages(group) + "?size=1"));
		assertThat(ids(get(alice, messages(direct) + "?cursor=" + groupCursor)))
				.contains(olderInDirect.toString())
				.isSubsetOf(inDirect.toString(), olderInDirect.toString());
	}

	@Test
	void nonMembersCannotReadTheHistory() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, bob);
		UUID direct = createDirect(alice, bob);
		send(alice, group, "Secret plans");
		send(alice, group, "More secret plans");
		send(alice, direct, "Secret plans");
		// A cursor a member received is no key either.
		String cursor = nextCursor(get(alice, messages(group) + "?size=1"));

		MvcTestResult groupResult = get(mallory, messages(group));
		MvcTestResult directResult = get(mallory, messages(direct));
		MvcTestResult withCursor = get(mallory, messages(group) + "?cursor=" + cursor);
		MvcTestResult unknownResult = get(mallory, messages(UUID.randomUUID()));

		assertNotFound(groupResult, directResult, withCursor, unknownResult);
		assertThat(withoutInstance(groupResult)).isEqualTo(withoutInstance(unknownResult));
		assertThat(body(groupResult)).doesNotContain("Secret plans", alice.id().toString());
		assertThat(body(directResult)).doesNotContain("Secret plans", alice.id().toString());
	}

	@Test
	void formerMembersCannotReadTheHistory() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		send(bob, group, "Bob's message");
		send(carol, group, "Carol's message");
		conversationService.leave(bob.id(), group);
		conversationService.removeMember(alice.id(), group, carol.id());

		// Including the messages they sent themselves.
		assertNotFound(get(bob, messages(group)), get(carol, messages(group)));
		// For the remaining members, the former members' messages stay, with their senders.
		MvcTestResult forAlice = get(alice, messages(group));
		assertThat(forAlice).bodyJson().extractingPath("$.content[*].sender.displayName").asArray()
				.containsExactlyInAnyOrder("Bob", "Carol");
	}

	@Test
	void nobodyCanReadTheHistoryOfADeletedGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		send(alice, group, "Hello");
		conversationService.deleteGroup(alice.id(), group);

		assertNotFound(get(alice, messages(group)), get(bob, messages(group)));
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

	private static String messages(UUID conversationId) {
		return "/api/v1/conversations/" + conversationId + "/messages";
	}

	private MvcTestResult send(TestUser caller, UUID conversationId, String content) {
		return postJson(caller, messages(conversationId), jsonMapper.writeValueAsString(new SendMessageRequest(content)));
	}

	/** Sends a message and then sets when it was stored, to control the history's order. */
	private UUID sendAt(TestUser caller, UUID conversationId, String content, OffsetDateTime createdAt) {
		MvcTestResult result = send(caller, conversationId, content);
		assertThat(result).hasStatus(HttpStatus.CREATED);
		UUID id = id(result);
		jdbcTemplate.update("update messages set created_at = ? where id = ?", createdAt, id);
		return id;
	}

	/** Truncated to microseconds, the precision PostgreSQL stores, so that equal times stay equal. */
	private static OffsetDateTime minutesAgo(int minutes) {
		return OffsetDateTime.now().minusMinutes(minutes).truncatedTo(ChronoUnit.MICROS);
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

	/** The message IDs on one page of a history, in order. */
	private List<String> ids(MvcTestResult result) {
		assertThat(result).hasStatusOk();
		List<String> ids = new ArrayList<>();
		json(result).get("content").forEach(entry -> ids.add(entry.get("id").asString()));
		return ids;
	}

	/** The page's {@code nextCursor}, or {@code null} if it has none. */
	private String nextCursor(MvcTestResult result) {
		assertThat(result).hasStatusOk();
		JsonNode cursor = json(result).get("nextCursor");
		return cursor.isNull() ? null : cursor.asString();
	}

	private UUID id(MvcTestResult result) {
		return UUID.fromString(json(result).get("id").asString());
	}

	private static void assertUnauthorized(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	/** The same 404 as for a conversation that doesn't exist. */
	private static void assertNotFound(MvcTestResult... results) {
		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
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

	/**
	 * Makes PostgreSQL reject every message stored in the conversation while {@code action} runs.
	 */
	private <T> T whileStoringMessagesFails(UUID conversationId, Supplier<T> action) {
		jdbcTemplate.execute("""
				create function simulated_failure() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated failure';
				end
				$$
				""");
		jdbcTemplate.execute("create trigger simulated_failure before insert on messages for each row when "
				+ "(new.conversation_id = '" + conversationId + "') execute function simulated_failure()");
		try {
			return action.get();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_failure on messages");
			jdbcTemplate.execute("drop function simulated_failure()");
		}
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

	private int messageCount(UUID conversationId) {
		return count("select count(*) from messages where conversation_id = ?", conversationId);
	}

}
