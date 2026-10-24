package com.adonis.chattywap.conversation;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import com.adonis.chattywap.conversation.dto.AddMembersRequest;
import com.adonis.chattywap.conversation.dto.CreateDirectConversationRequest;
import com.adonis.chattywap.conversation.dto.CreateGroupConversationRequest;
import com.adonis.chattywap.conversation.dto.RenameGroupRequest;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation endpoints through HTTP, with access tokens validated by the real security configuration,
 * against PostgreSQL in a container. Tests share one database, so each test creates its own users and only
 * looks at their conversations.
 * <p>
 * Users are created through {@link UserService} and get tokens from the application's {@link JwtEncoder}, which
 * skips the deliberately slow password hashing of registration and login. One test uses a real login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ConversationControllerIT {

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
	private JwtEncoder jwtEncoder;

	@Value("${chattywap.auth.jwt-issuer}")
	private String issuer;

	@Value("${chattywap.auth.jwt-audience}")
	private String audience;

	// ---------------------------------------------------------------------------------------------------------
	// Direct conversations

	@Test
	void createDirectConversationAddsBothUsers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");

		MvcTestResult result = post(alice, CONVERSATIONS + "/direct", new CreateDirectConversationRequest(bob.id()));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("DIRECT");
		assertThat(result).bodyJson().extractingPath("$.name").isNull();
		assertThat(result).bodyJson().extractingPath("$.createdBy").isEqualTo(alice.id().toString());
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactlyInAnyOrder(alice.id().toString(), bob.id().toString());
		assertThat(result).bodyJson().extractingPath("$.members[*].displayName").asArray()
				.containsExactlyInAnyOrder("Alice", "Bob");
		assertThat(result).bodyJson().extractingPath("$.members[*].role").asArray().containsOnly("MEMBER");
		// Members see each other's display names, never their email addresses.
		assertThat(body(result)).doesNotContain("@example.com");
		assertThat(memberCount(id(result))).isEqualTo(2);
	}

	@Test
	void askingForAnExistingDirectConversationReturnsItInsteadOfCreatingAnother() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID conversationId = createDirect(alice, bob);

		MvcTestResult again = post(alice, CONVERSATIONS + "/direct", new CreateDirectConversationRequest(bob.id()));
		MvcTestResult reversed = post(bob, CONVERSATIONS + "/direct", new CreateDirectConversationRequest(alice.id()));

		assertThat(again).hasStatusOk();
		assertThat(again).bodyJson().extractingPath("$.id").isEqualTo(conversationId.toString());
		assertThat(reversed).hasStatusOk();
		assertThat(reversed).bodyJson().extractingPath("$.id").isEqualTo(conversationId.toString());
		assertThat(count("""
				select count(*) from conversations
				where type = 'DIRECT' and ? in (direct_user1_id, direct_user2_id) and ? in (direct_user1_id, direct_user2_id)
				""", alice.id(), bob.id())).isEqualTo(1);
	}

	@Test
	void directConversationWithYourselfIsRejected() {
		TestUser alice = createUser("Alice");

		MvcTestResult result = post(alice, CONVERSATIONS + "/direct", new CreateDirectConversationRequest(alice.id()));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("A direct conversation needs another user as recipient.");
		assertThat(conversationsCreatedBy(alice)).isZero();
	}

	@Test
	void directConversationWithUnknownRecipientIsRejected() {
		TestUser alice = createUser("Alice");
		UUID unknown = UUID.randomUUID();

		MvcTestResult result = post(alice, CONVERSATIONS + "/direct", new CreateDirectConversationRequest(unknown));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Some users do not exist.");
		assertThat(result).bodyJson().extractingPath("$.userIds").asArray().containsExactly(unknown.toString());
		assertThat(conversationsCreatedBy(alice)).isZero();
	}

	@Test
	void directConversationRequiresARecipient() {
		TestUser alice = createUser("Alice");

		MvcTestResult result = postJson(alice, CONVERSATIONS + "/direct", "{}");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("recipientId");
	}

	// ---------------------------------------------------------------------------------------------------------
	// Group conversations

	@Test
	void createGroupMakesTheCreatorAdministratorAndAddsTheOthersAsMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");

		MvcTestResult result = post(alice, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest("  Weekend Plans  ", List.of(bob.id(), carol.id())));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("GROUP");
		assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Weekend Plans");
		assertThat(result).bodyJson().extractingPath("$.createdBy").isEqualTo(alice.id().toString());
		assertThat(result).bodyJson().extractingPath("$.members[?(@.role == 'ADMIN')].userId").asArray()
				.containsExactly(alice.id().toString());
		assertThat(result).bodyJson().extractingPath("$.members[?(@.role == 'MEMBER')].userId").asArray()
				.containsExactlyInAnyOrder(bob.id().toString(), carol.id().toString());
		assertThat(memberCount(id(result))).isEqualTo(3);
	}

	@Test
	void createGroupIgnoresDuplicateIdsAndTheCreatorsOwnId() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");

		MvcTestResult result = post(alice, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest("Duplicates", List.of(bob.id(), alice.id(), bob.id())));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactlyInAnyOrder(alice.id().toString(), bob.id().toString());
		assertThat(result).bodyJson().extractingPath("$.members[?(@.role == 'ADMIN')].userId").asArray()
				.containsExactly(alice.id().toString());
		assertThat(memberCount(id(result))).isEqualTo(2);
	}

	@Test
	void groupWithoutOtherMembersIsAllowed() {
		TestUser alice = createUser("Alice");

		MvcTestResult result = post(alice, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest("Just me", List.of()));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactly(alice.id().toString());
	}

	@Test
	void groupWithUnknownMembersIsRejectedAndNothingIsStored() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID unknown = UUID.randomUUID();

		MvcTestResult result = post(alice, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest("Weekend Plans", List.of(bob.id(), unknown)));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.userIds").asArray().containsExactly(unknown.toString());
		assertThat(conversationsCreatedBy(alice)).isZero();
	}

	@Test
	void createGroupValidatesNameAndMemberIds() {
		TestUser alice = createUser("Alice");

		MvcTestResult missing = postJson(alice, CONVERSATIONS + "/group", "{\"name\": \"   \"}");
		MvcTestResult tooLong = post(alice, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest("x".repeat(101), List.of()));
		MvcTestResult nullMember = postJson(alice, CONVERSATIONS + "/group",
				"{\"name\": \"Group\", \"memberIds\": [\"" + UUID.randomUUID() + "\", null]}");
		MvcTestResult tooManyMembers = post(alice, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest("Group", Stream.generate(UUID::randomUUID).limit(101).toList()));
		MvcTestResult malformedId = postJson(alice, CONVERSATIONS + "/group",
				"{\"name\": \"Group\", \"memberIds\": [\"not-a-uuid\"]}");

		assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(missing).bodyJson().extractingPath("$.errors[*].field").asArray()
				.containsExactly("memberIds", "name");
		assertThat(tooLong).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(tooLong).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("name");
		assertThat(nullMember).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(nullMember).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("memberIds[1]");
		assertThat(tooManyMembers).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(tooManyMembers).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("memberIds");
		assertThat(malformedId).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(malformedId).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(conversationsCreatedBy(alice)).isZero();
	}

	@Test
	void theCallerIsAlwaysTheTokensUser() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");

		// Attempts to name another user as creator, in the query string and in the body, are ignored.
		MvcTestResult result = mvc.post().uri(CONVERSATIONS + "/group?userId={id}&createdBy={id}", bob.id(), bob.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Mine\", \"memberIds\": [], \"createdBy\": \"" + bob.id() + "\"}")
				.exchange();

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.createdBy").isEqualTo(alice.id().toString());
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactly(alice.id().toString());
	}

	@Test
	void worksWithTheAccessTokenFromARealLogin() throws Exception {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		String password = "correct horse battery staple";
		assertThat(postAnonymous("/api/v1/auth/register", new RegisterRequest(email, password, "Alice")))
				.hasStatus(HttpStatus.CREATED);
		MvcTestResult login = postAnonymous("/api/v1/auth/login", new LoginRequest(email, password));
		String accessToken = jsonMapper.readValue(body(login), TokenResponse.class).accessToken();
		TestUser bob = createUser("Bob");

		MvcTestResult result = mvc.post().uri(CONVERSATIONS + "/direct")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(new CreateDirectConversationRequest(bob.id())))
				.exchange();

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.members[*].displayName").asArray()
				.containsExactlyInAnyOrder("Alice", "Bob");
	}

	@Test
	void everyConversationEndpointRequiresAnAccessToken() {
		String id = UUID.randomUUID().toString();
		String json = "{}";

		assertUnauthorized(mvc.post().uri(CONVERSATIONS + "/direct").contentType(MediaType.APPLICATION_JSON).content(json).exchange());
		assertUnauthorized(mvc.post().uri(CONVERSATIONS + "/group").contentType(MediaType.APPLICATION_JSON).content(json).exchange());
		assertUnauthorized(mvc.get().uri(CONVERSATIONS).exchange());
		assertUnauthorized(mvc.get().uri(CONVERSATIONS + "/" + id).exchange());
		assertUnauthorized(mvc.post().uri(CONVERSATIONS + "/" + id + "/members").contentType(MediaType.APPLICATION_JSON).content(json).exchange());
		assertUnauthorized(mvc.delete().uri(CONVERSATIONS + "/" + id + "/members/" + id).exchange());
		assertUnauthorized(mvc.post().uri(CONVERSATIONS + "/" + id + "/members/" + id + "/promote").exchange());
		assertUnauthorized(mvc.post().uri(CONVERSATIONS + "/" + id + "/members/" + id + "/demote").exchange());
		assertUnauthorized(mvc.put().uri(CONVERSATIONS + "/" + id + "/name").contentType(MediaType.APPLICATION_JSON).content(json).exchange());
		assertUnauthorized(mvc.delete().uri(CONVERSATIONS + "/" + id).exchange());
		assertUnauthorized(mvc.post().uri(CONVERSATIONS + "/" + id + "/leave").exchange());
		assertUnauthorized(mvc.get().uri(CONVERSATIONS).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").exchange());
	}

	// ---------------------------------------------------------------------------------------------------------
	// Listing

	@Test
	void listShowsOnlyTheCallersConversations() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID aliceAndBob = createDirect(alice, bob);
		UUID alicesGroup = createGroup(alice, "Alice's group", carol);
		UUID bobAndCarol = createDirect(bob, carol);

		MvcTestResult forAlice = get(alice, CONVERSATIONS);
		MvcTestResult forCarol = get(carol, CONVERSATIONS);

		assertThat(forAlice).hasStatusOk();
		assertThat(forAlice).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactlyInAnyOrder(aliceAndBob.toString(), alicesGroup.toString());
		assertThat(forAlice).bodyJson().extractingPath("$.page.totalElements").isEqualTo(2);
		assertThat(forCarol).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactlyInAnyOrder(alicesGroup.toString(), bobAndCarol.toString());
	}

	@Test
	void listEntriesShowTheCallersRoleTheMemberCountAndTheOtherUserOfDirectConversations() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID direct = createDirect(alice, bob);
		UUID group = createGroup(bob, "Team", alice, carol);
		setTimes(direct, OffsetDateTime.now().minusMinutes(2));
		setTimes(group, OffsetDateTime.now().minusMinutes(1));

		MvcTestResult result = get(alice, CONVERSATIONS);

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.content[0].id").isEqualTo(group.toString());
		assertThat(result).bodyJson().extractingPath("$.content[0].type").isEqualTo("GROUP");
		assertThat(result).bodyJson().extractingPath("$.content[0].name").isEqualTo("Team");
		assertThat(result).bodyJson().extractingPath("$.content[0].role").isEqualTo("MEMBER");
		assertThat(result).bodyJson().extractingPath("$.content[0].memberCount").isEqualTo(3);
		assertThat(result).bodyJson().extractingPath("$.content[0].otherUser").isNull();
		assertThat(result).bodyJson().extractingPath("$.content[1].id").isEqualTo(direct.toString());
		assertThat(result).bodyJson().extractingPath("$.content[1].type").isEqualTo("DIRECT");
		assertThat(result).bodyJson().extractingPath("$.content[1].name").isNull();
		assertThat(result).bodyJson().extractingPath("$.content[1].memberCount").isEqualTo(2);
		assertThat(result).bodyJson().extractingPath("$.content[1].otherUser.id").isEqualTo(bob.id().toString());
		assertThat(result).bodyJson().extractingPath("$.content[1].otherUser.displayName").isEqualTo("Bob");
		assertThat(body(result)).doesNotContain("@example.com");
	}

	@Test
	void listIsPaginatedMostRecentlyUpdatedFirst() {
		TestUser alice = createUser("Alice");
		UUID oldest = createGroup(alice, "Oldest");
		UUID middle = createGroup(alice, "Middle");
		UUID newest = createGroup(alice, "Newest");
		setTimes(oldest, OffsetDateTime.now().minusMinutes(3));
		setTimes(middle, OffsetDateTime.now().minusMinutes(2));
		setTimes(newest, OffsetDateTime.now().minusMinutes(1));

		MvcTestResult first = get(alice, CONVERSATIONS + "?page=0&size=2");
		MvcTestResult second = get(alice, CONVERSATIONS + "?page=1&size=2");
		MvcTestResult beyond = get(alice, CONVERSATIONS + "?page=2&size=2");

		assertThat(first).hasStatusOk();
		assertThat(first).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactly(newest.toString(), middle.toString());
		assertThat(first).bodyJson().extractingPath("$.page.size").isEqualTo(2);
		assertThat(first).bodyJson().extractingPath("$.page.number").isEqualTo(0);
		assertThat(first).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
		assertThat(first).bodyJson().extractingPath("$.page.totalPages").isEqualTo(2);
		assertThat(second).bodyJson().extractingPath("$.content[*].id").asArray().containsExactly(oldest.toString());
		assertThat(second).bodyJson().extractingPath("$.page.number").isEqualTo(1);
		assertThat(beyond).hasStatusOk();
		assertThat(beyond).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(beyond).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
	}

	@Test
	void aPageWhoseOffsetExceedsTheIntegerRangeIsEmpty() {
		TestUser alice = createUser("Alice");
		createGroup(alice, "Group");

		// 200,000,000 pages of 20 entries: an offset of 4 billion.
		MvcTestResult result = get(alice, CONVERSATIONS + "?page=200000000&size=20");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(1);
	}

	@Test
	void conversationsUpdatedAtTheSameMomentAreOrderedById() {
		TestUser alice = createUser("Alice");
		UUID first = createGroup(alice, "First");
		UUID second = createGroup(alice, "Second");
		OffsetDateTime sameTime = OffsetDateTime.now().minusMinutes(1);
		setTimes(first, sameTime);
		setTimes(second, sameTime);
		// PostgreSQL orders UUIDs like their lower-case text, which is how UUID.toString() writes them.
		List<String> expected = Stream.of(first, second).map(UUID::toString)
				.sorted(Comparator.reverseOrder()).toList();

		assertThat(get(alice, CONVERSATIONS + "?size=1")).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactly(expected.get(0));
		assertThat(get(alice, CONVERSATIONS + "?page=1&size=1")).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactly(expected.get(1));
	}

	@Test
	void membershipChangesMoveAConversationToTheTop() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID older = createGroup(alice, "Older");
		UUID newer = createGroup(alice, "Newer");
		setTimes(older, OffsetDateTime.now().minusMinutes(2));
		setTimes(newer, OffsetDateTime.now().minusMinutes(1));

		assertThat(post(alice, CONVERSATIONS + "/" + older + "/members", new AddMembersRequest(List.of(bob.id()))))
				.hasStatusOk();

		assertThat(get(alice, CONVERSATIONS)).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactly(older.toString(), newer.toString());
	}

	@Test
	void pageSizeIsCappedAndInvalidValuesFallBackToTheDefaults() {
		TestUser alice = createUser("Alice");

		MvcTestResult tooLarge = get(alice, CONVERSATIONS + "?size=1000");
		MvcTestResult invalid = get(alice, CONVERSATIONS + "?page=-1&size=0");
		MvcTestResult notNumbers = get(alice, CONVERSATIONS + "?page=first&size=many");

		assertThat(tooLarge).hasStatusOk();
		assertThat(tooLarge).bodyJson().extractingPath("$.page.size").isEqualTo(100);
		for (MvcTestResult result : List.of(invalid, notNumbers)) {
			assertThat(result).hasStatusOk();
			assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
			assertThat(result).bodyJson().extractingPath("$.page.size").isEqualTo(20);
		}
	}

	@Test
	void membersAreOrderedByJoinTimeThenUserId() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);
		OffsetDateTime sameTime = OffsetDateTime.now().minusMinutes(1);
		setJoinedAt(group, bob, sameTime);
		setJoinedAt(group, carol, sameTime);
		List<String> joinedTogether = Stream.of(bob, carol).map(user -> user.id().toString()).sorted().toList();

		MvcTestResult result = get(alice, CONVERSATIONS + "/" + group);

		// Alice joined at creation, after the time set for Bob and Carol.
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactly(joinedTogether.get(0), joinedTogether.get(1), alice.id().toString());
	}

	// ---------------------------------------------------------------------------------------------------------
	// Details

	@Test
	void membersSeeTheConversationWithAllMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Weekend Plans", bob, carol);

		MvcTestResult result = get(bob, CONVERSATIONS + "/" + group);

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(group.toString());
		assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Weekend Plans");
		assertThat(result).bodyJson().extractingPath("$.members[*].displayName").asArray()
				.containsExactlyInAnyOrder("Alice", "Bob", "Carol");
		assertThat(result).bodyJson().hasPath("$.members[0].joinedAt");
		assertThat(result).bodyJson().hasPath("$.createdAt");
		assertThat(result).bodyJson().hasPath("$.updatedAt");
		assertThat(body(result)).doesNotContain("@example.com");
	}

	@Test
	void nonMembersGetTheSameAnswerAsForAConversationThatDoesNotExist() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Private plans", bob);
		UUID direct = createDirect(alice, bob);

		MvcTestResult groupResult = get(mallory, CONVERSATIONS + "/" + group);
		MvcTestResult directResult = get(mallory, CONVERSATIONS + "/" + direct);
		MvcTestResult unknownResult = get(mallory, CONVERSATIONS + "/" + UUID.randomUUID());

		for (MvcTestResult result : List.of(groupResult, directResult, unknownResult)) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
		}
		assertThat(body(groupResult)).doesNotContain("Private plans", alice.id().toString());
		assertThat(withoutInstance(groupResult)).isEqualTo(withoutInstance(unknownResult));
	}

	@Test
	void malformedConversationIdIsABadRequest() {
		TestUser alice = createUser("Alice");

		MvcTestResult result = get(alice, CONVERSATIONS + "/not-a-uuid");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Adding members

	@Test
	void administratorsCanAddMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		TestUser dave = createUser("Dave");
		UUID group = createGroup(alice, "Group", bob);

		MvcTestResult result = post(alice, CONVERSATIONS + "/" + group + "/members",
				new AddMembersRequest(List.of(carol.id(), dave.id())));

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray().containsExactlyInAnyOrder(
				alice.id().toString(), bob.id().toString(), carol.id().toString(), dave.id().toString());
		assertThat(role(group, carol)).isEqualTo("MEMBER");
		assertThat(role(group, dave)).isEqualTo("MEMBER");
		assertThat(get(carol, CONVERSATIONS + "/" + group)).hasStatusOk();
	}

	@Test
	void addingExistingMembersIsIgnored() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob);
		OffsetDateTime updatedAt = updatedAt(group);

		MvcTestResult nothingNew = post(alice, CONVERSATIONS + "/" + group + "/members",
				new AddMembersRequest(List.of(bob.id(), alice.id())));

		assertThat(nothingNew).hasStatusOk();
		assertThat(memberCount(group)).isEqualTo(2);
		assertThat(updatedAt(group)).isEqualTo(updatedAt);

		MvcTestResult oneNew = post(alice, CONVERSATIONS + "/" + group + "/members",
				new AddMembersRequest(List.of(bob.id(), carol.id(), carol.id())));

		assertThat(oneNew).hasStatusOk();
		assertThat(memberCount(group)).isEqualTo(3);
		assertThat(role(group, bob)).isEqualTo("MEMBER");
	}

	@Test
	void addingUnknownUsersIsRejectedAndAddsNobody() {
		TestUser alice = createUser("Alice");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group");
		UUID unknown = UUID.randomUUID();

		MvcTestResult result = post(alice, CONVERSATIONS + "/" + group + "/members",
				new AddMembersRequest(List.of(carol.id(), unknown)));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.userIds").asArray().containsExactly(unknown.toString());
		assertThat(memberCount(group)).isEqualTo(1);
	}

	@Test
	void membersWhoAreNotAdministratorsCannotAddMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob);

		MvcTestResult result = post(bob, CONVERSATIONS + "/" + group + "/members",
				new AddMembersRequest(List.of(carol.id())));

		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("Only group administrators can add or remove other members.");
		assertThat(memberCount(group)).isEqualTo(2);
	}

	@Test
	void nonMembersCannotAddMembers() {
		TestUser alice = createUser("Alice");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Group");

		MvcTestResult result = post(mallory, CONVERSATIONS + "/" + group + "/members",
				new AddMembersRequest(List.of(mallory.id())));

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
		assertThat(memberCount(group)).isEqualTo(1);
	}

	@Test
	void addMembersRequiresAtLeastOneUser() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Group");

		MvcTestResult result = post(alice, CONVERSATIONS + "/" + group + "/members", new AddMembersRequest(List.of()));

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("userIds");
	}

	// ---------------------------------------------------------------------------------------------------------
	// Removing members

	@Test
	void administratorsCanRemoveMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);

		MvcTestResult result = delete(alice, CONVERSATIONS + "/" + group + "/members/" + bob.id());

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(get(bob, CONVERSATIONS + "/" + group)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(get(bob, CONVERSATIONS)).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(get(alice, CONVERSATIONS + "/" + group)).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactlyInAnyOrder(alice.id().toString(), carol.id().toString());
	}

	@Test
	void administratorsCanRemoveOtherAdministrators() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);
		makeAdmin(group, bob);

		assertThat(delete(alice, CONVERSATIONS + "/" + group + "/members/" + bob.id())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(role(group, bob)).isNull();
		assertThat(role(group, alice)).isEqualTo("ADMIN");
	}

	@Test
	void membersWhoAreNotAdministratorsCannotRemoveOthers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);

		MvcTestResult removeMember = delete(bob, CONVERSATIONS + "/" + group + "/members/" + carol.id());
		MvcTestResult removeAdmin = delete(bob, CONVERSATIONS + "/" + group + "/members/" + alice.id());

		assertThat(removeMember).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(removeAdmin).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(memberCount(group)).isEqualTo(3);
	}

	@Test
	void nonMembersCannotRemoveMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Group", bob);

		MvcTestResult result = delete(mallory, CONVERSATIONS + "/" + group + "/members/" + bob.id());

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
		assertThat(memberCount(group)).isEqualTo(2);
	}

	@Test
	void removingSomeoneWhoIsNotAMemberIsNotFound() {
		TestUser alice = createUser("Alice");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group");

		MvcTestResult result = delete(alice, CONVERSATIONS + "/" + group + "/members/" + carol.id());

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("The user is not a member of this conversation.");
	}

	@Test
	void membersCanRemoveThemselves() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);

		assertThat(delete(bob, CONVERSATIONS + "/" + group + "/members/" + bob.id())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(role(group, bob)).isNull();
	}

	@Test
	void lastAdministratorRemovingThemselvesHandsTheRoleOver() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);

		assertThat(delete(alice, CONVERSATIONS + "/" + group + "/members/" + alice.id())).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(role(group, alice)).isNull();
		assertThat(role(group, bob)).isEqualTo("ADMIN");
	}

	@Test
	void directConversationsHaveNoMemberManagement() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID direct = createDirect(alice, bob);

		List<MvcTestResult> results = List.of(
				post(alice, CONVERSATIONS + "/" + direct + "/members", new AddMembersRequest(List.of(carol.id()))),
				delete(alice, CONVERSATIONS + "/" + direct + "/members/" + bob.id()),
				delete(alice, CONVERSATIONS + "/" + direct + "/members/" + alice.id()),
				post(alice, CONVERSATIONS + "/" + direct + "/leave"));

		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.CONFLICT);
			assertThat(result).bodyJson().extractingPath("$.detail")
					.isEqualTo("Members can only be added, removed or leave in group conversations.");
		}
		assertThat(memberCount(direct)).isEqualTo(2);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Leaving

	@Test
	void membersCanLeaveAGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);

		MvcTestResult result = post(bob, CONVERSATIONS + "/" + group + "/leave");

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(get(bob, CONVERSATIONS + "/" + group)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(get(bob, CONVERSATIONS)).bodyJson().extractingPath("$.content").asArray().isEmpty();
		assertThat(role(group, alice)).isEqualTo("ADMIN");
		assertThat(role(group, carol)).isEqualTo("MEMBER");
	}

	@Test
	void leavingAConversationYouDoNotBelongToIsNotFound() {
		TestUser alice = createUser("Alice");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Group");

		MvcTestResult notAMember = post(mallory, CONVERSATIONS + "/" + group + "/leave");
		MvcTestResult unknown = post(mallory, CONVERSATIONS + "/" + UUID.randomUUID() + "/leave");

		assertThat(notAMember).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(withoutInstance(notAMember)).isEqualTo(withoutInstance(unknown));
		assertThat(memberCount(group)).isEqualTo(1);
	}

	@Test
	void whenTheLastAdministratorLeavesTheLongestStandingMemberBecomesAdministrator() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", carol, bob);
		// Bob joined before Carol, even though Carol is listed first.
		setJoinedAt(group, bob, OffsetDateTime.now().minusMinutes(2));
		setJoinedAt(group, carol, OffsetDateTime.now().minusMinutes(1));

		assertThat(post(alice, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(role(group, bob)).isEqualTo("ADMIN");
		assertThat(role(group, carol)).isEqualTo("MEMBER");
		assertThat(adminCount(group)).isEqualTo(1);
	}

	@Test
	void anAdministratorLeavingWhileAnotherRemainsPromotesNobody() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);
		makeAdmin(group, carol);

		assertThat(post(alice, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(role(group, bob)).isEqualTo("MEMBER");
		assertThat(role(group, carol)).isEqualTo("ADMIN");
	}

	@Test
	void whenTheLastMemberLeavesTheGroupIsDeleted() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Group");

		assertThat(post(alice, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(memberCount(group)).isZero();
		assertThat(isDeleted(group)).isTrue();
		assertThat(get(alice, CONVERSATIONS + "/" + group)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(get(alice, CONVERSATIONS)).bodyJson().extractingPath("$.content").asArray().isEmpty();
	}

	@Test
	void whenTheLastMemberRemovesThemselvesTheGroupIsDeleted() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Group");

		assertThat(delete(alice, CONVERSATIONS + "/" + group + "/members/" + alice.id()))
				.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(memberCount(group)).isZero();
		assertThat(isDeleted(group)).isTrue();
	}

	@Test
	void aGroupIsNotDeletedWhileItHasMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);

		assertThat(post(alice, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(isDeleted(group)).isFalse();
		assertThat(role(group, bob)).isEqualTo("ADMIN");
		assertThat(get(bob, CONVERSATIONS + "/" + group)).hasStatusOk();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Administrators

	@Test
	void administratorsCanPromoteMembers() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);
		OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);
		setTimes(group, before);

		MvcTestResult result = post(alice, member(group, bob) + "/promote");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.members[?(@.role == 'ADMIN')].userId").asArray()
				.containsExactlyInAnyOrder(alice.id().toString(), bob.id().toString());
		assertThat(role(group, bob)).isEqualTo("ADMIN");
		assertThat(role(group, carol)).isEqualTo("MEMBER");
		assertThat(updatedAt(group)).isAfter(before);
		// The new administrator can do what administrators do.
		assertThat(post(bob, member(group, carol) + "/promote")).hasStatusOk();
	}

	@Test
	void administratorsCanDemoteOtherAdministrators() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);
		makeAdmin(group, bob);

		MvcTestResult result = post(alice, member(group, bob) + "/demote");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.members[?(@.role == 'ADMIN')].userId").asArray()
				.containsExactly(alice.id().toString());
		assertThat(role(group, bob)).isEqualTo("MEMBER");
		assertThat(role(group, alice)).isEqualTo("ADMIN");
	}

	@Test
	void administratorsCanDemoteThemselvesWhileAnotherAdministratorRemains() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);
		makeAdmin(group, bob);

		assertThat(post(alice, member(group, alice) + "/demote")).hasStatusOk();

		assertThat(role(group, alice)).isEqualTo("MEMBER");
		assertThat(role(group, bob)).isEqualTo("ADMIN");
	}

	@Test
	void theLastAdministratorCannotDemoteThemselves() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);
		OffsetDateTime updatedAt = updatedAt(group);

		MvcTestResult result = post(alice, member(group, alice) + "/demote");

		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("A group needs an administrator. Promote another member before demoting the last administrator.");
		assertThat(role(group, alice)).isEqualTo("ADMIN");
		assertThat(updatedAt(group)).isEqualTo(updatedAt);
	}

	@Test
	void theLastAdministratorCanHandTheRoleOverBeforeLeaving() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);
		// Bob joined first and would get the role automatically; Alice chooses Carol instead.
		setJoinedAt(group, bob, OffsetDateTime.now().minusMinutes(2));

		assertThat(post(alice, member(group, carol) + "/promote")).hasStatusOk();
		assertThat(post(alice, member(group, alice) + "/demote")).hasStatusOk();
		assertThat(post(alice, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(role(group, carol)).isEqualTo("ADMIN");
		assertThat(role(group, bob)).isEqualTo("MEMBER");
	}

	@Test
	void membersWhoAreNotAdministratorsCannotChangeRoles() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);

		List<MvcTestResult> results = List.of(
				post(bob, member(group, carol) + "/promote"),
				post(bob, member(group, bob) + "/promote"),
				post(bob, member(group, alice) + "/demote"));

		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
			assertThat(result).bodyJson().extractingPath("$.detail")
					.isEqualTo("Only group administrators can promote or demote members.");
		}
		assertThat(adminCount(group)).isEqualTo(1);
		assertThat(role(group, alice)).isEqualTo("ADMIN");
	}

	@Test
	void nonMembersCannotChangeRoles() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Group", bob);

		MvcTestResult promote = post(mallory, member(group, bob) + "/promote");
		MvcTestResult demote = post(mallory, member(group, alice) + "/demote");
		MvcTestResult unknown = post(mallory, CONVERSATIONS + "/" + UUID.randomUUID() + "/members/" + bob.id() + "/promote");

		for (MvcTestResult result : List.of(promote, demote, unknown)) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
		}
		assertThat(role(group, bob)).isEqualTo("MEMBER");
		assertThat(role(group, alice)).isEqualTo("ADMIN");
	}

	@Test
	void promotingAnAdministratorOrDemotingAMemberIsAConflict() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);
		OffsetDateTime updatedAt = updatedAt(group);

		MvcTestResult promoteAdmin = post(alice, member(group, alice) + "/promote");
		MvcTestResult demoteMember = post(alice, member(group, bob) + "/demote");

		assertThat(promoteAdmin).hasStatus(HttpStatus.CONFLICT);
		assertThat(promoteAdmin).bodyJson().extractingPath("$.detail")
				.isEqualTo("The user is already an administrator of this group.");
		assertThat(demoteMember).hasStatus(HttpStatus.CONFLICT);
		assertThat(demoteMember).bodyJson().extractingPath("$.detail")
				.isEqualTo("The user is not an administrator of this group.");
		assertThat(role(group, alice)).isEqualTo("ADMIN");
		assertThat(role(group, bob)).isEqualTo("MEMBER");
		assertThat(updatedAt(group)).isEqualTo(updatedAt);
	}

	@Test
	void changingTheRoleOfSomeoneWhoIsNotAMemberIsNotFound() {
		TestUser alice = createUser("Alice");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group");

		MvcTestResult promote = post(alice, member(group, carol) + "/promote");
		MvcTestResult demote = post(alice, member(group, carol) + "/demote");

		for (MvcTestResult result : List.of(promote, demote)) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).bodyJson().extractingPath("$.detail")
					.isEqualTo("The user is not a member of this conversation.");
		}
		assertThat(role(group, carol)).isNull();
	}

	@Test
	void directConversationsHaveNoAdministrators() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = createDirect(alice, bob);

		MvcTestResult promote = post(alice, member(direct, bob) + "/promote");
		MvcTestResult demote = post(alice, member(direct, alice) + "/demote");

		for (MvcTestResult result : List.of(promote, demote)) {
			assertThat(result).hasStatus(HttpStatus.CONFLICT);
			assertThat(result).bodyJson().extractingPath("$.detail")
					.isEqualTo("Only group conversations have administrators.");
		}
		assertThat(adminCount(direct)).isZero();
	}

	@Test
	void roleChangesRequireWellFormedIds() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Group");

		assertThat(post(alice, CONVERSATIONS + "/" + group + "/members/not-a-uuid/promote"))
				.hasStatus(HttpStatus.BAD_REQUEST);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Renaming

	@Test
	void administratorsCanRenameAGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Old name", bob);
		OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);
		setTimes(group, before);

		MvcTestResult result = put(alice, CONVERSATIONS + "/" + group + "/name", new RenameGroupRequest("  New name  "));

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("New name");
		assertThat(result).bodyJson().extractingPath("$.members[*].userId").asArray()
				.containsExactlyInAnyOrder(alice.id().toString(), bob.id().toString());
		assertThat(name(group)).isEqualTo("New name");
		assertThat(updatedAt(group)).isAfter(before);
		assertThat(get(bob, CONVERSATIONS + "/" + group)).bodyJson().extractingPath("$.name").isEqualTo("New name");
	}

	@Test
	void renamingAGroupToItsCurrentNameChangesNothing() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Name");
		OffsetDateTime updatedAt = updatedAt(group);

		MvcTestResult result = put(alice, CONVERSATIONS + "/" + group + "/name", new RenameGroupRequest(" Name "));

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Name");
		assertThat(updatedAt(group)).isEqualTo(updatedAt);
	}

	@Test
	void renameValidatesTheName() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Name");
		String uri = CONVERSATIONS + "/" + group + "/name";

		MvcTestResult blank = putJson(alice, uri, "{\"name\": \"   \"}");
		MvcTestResult missing = putJson(alice, uri, "{}");
		MvcTestResult tooLong = put(alice, uri, new RenameGroupRequest("x".repeat(101)));
		MvcTestResult malformed = putJson(alice, uri, "{\"name\": ");

		for (MvcTestResult result : List.of(blank, missing, tooLong)) {
			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("name");
		}
		assertThat(malformed).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(malformed).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(name(group)).isEqualTo("Name");

		assertThat(put(alice, uri, new RenameGroupRequest("x".repeat(100)))).hasStatusOk();
		assertThat(name(group)).isEqualTo("x".repeat(100));
	}

	@Test
	void membersWhoAreNotAdministratorsCannotRenameAGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Name", bob);

		MvcTestResult result = put(bob, CONVERSATIONS + "/" + group + "/name", new RenameGroupRequest("Bob's group"));

		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("Only group administrators can rename the group.");
		assertThat(name(group)).isEqualTo("Name");
	}

	@Test
	void nonMembersCannotRenameAGroup() {
		TestUser alice = createUser("Alice");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Name");

		MvcTestResult notAMember = put(mallory, CONVERSATIONS + "/" + group + "/name", new RenameGroupRequest("Mine"));
		MvcTestResult unknown = put(mallory, CONVERSATIONS + "/" + UUID.randomUUID() + "/name",
				new RenameGroupRequest("Mine"));

		assertThat(notAMember).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(withoutInstance(notAMember)).isEqualTo(withoutInstance(unknown));
		assertThat(name(group)).isEqualTo("Name");
	}

	@Test
	void directConversationsCannotBeRenamed() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = createDirect(alice, bob);

		MvcTestResult result = put(alice, CONVERSATIONS + "/" + direct + "/name", new RenameGroupRequest("Us"));

		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Only group conversations have a name.");
		assertThat(name(direct)).isNull();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Deleting

	@Test
	void administratorsCanDeleteAGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob, carol);

		MvcTestResult result = delete(alice, CONVERSATIONS + "/" + group);

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		for (TestUser user : List.of(alice, bob, carol)) {
			assertThat(get(user, CONVERSATIONS + "/" + group)).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(get(user, CONVERSATIONS)).bodyJson().extractingPath("$.content").asArray().isEmpty();
		}
		// The memberships are gone; the conversation row stays, marked deleted.
		assertThat(memberCount(group)).isZero();
		assertThat(isDeleted(group)).isTrue();
		assertThat(name(group)).isEqualTo("Group");
	}

	@Test
	void aDeletedGroupCannotBeChangedOrDeletedAgain() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, "Group", bob);
		assertThat(delete(alice, CONVERSATIONS + "/" + group)).hasStatus(HttpStatus.NO_CONTENT);

		List<MvcTestResult> results = List.of(
				post(alice, CONVERSATIONS + "/" + group + "/members", new AddMembersRequest(List.of(carol.id()))),
				delete(alice, member(group, bob)),
				post(alice, member(group, bob) + "/promote"),
				put(alice, CONVERSATIONS + "/" + group + "/name", new RenameGroupRequest("Back")),
				post(bob, CONVERSATIONS + "/" + group + "/leave"),
				delete(alice, CONVERSATIONS + "/" + group));

		for (MvcTestResult result : results) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Conversation not found.");
		}
		assertThat(memberCount(group)).isZero();
		assertThat(name(group)).isEqualTo("Group");
	}

	@Test
	void membersWhoAreNotAdministratorsCannotDeleteAGroup() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, "Group", bob);

		MvcTestResult result = delete(bob, CONVERSATIONS + "/" + group);

		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("Only group administrators can delete the group.");
		assertThat(memberCount(group)).isEqualTo(2);
		assertThat(isDeleted(group)).isFalse();
	}

	@Test
	void nonMembersCannotDeleteAGroup() {
		TestUser alice = createUser("Alice");
		TestUser mallory = createUser("Mallory");
		UUID group = createGroup(alice, "Group");

		MvcTestResult notAMember = delete(mallory, CONVERSATIONS + "/" + group);
		MvcTestResult unknown = delete(mallory, CONVERSATIONS + "/" + UUID.randomUUID());

		assertThat(notAMember).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(withoutInstance(notAMember)).isEqualTo(withoutInstance(unknown));
		assertThat(isDeleted(group)).isFalse();
	}

	@Test
	void directConversationsCannotBeDeleted() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID direct = createDirect(alice, bob);

		MvcTestResult result = delete(alice, CONVERSATIONS + "/" + direct);

		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Only group conversations can be deleted.");
		assertThat(memberCount(direct)).isEqualTo(2);
		assertThat(get(bob, CONVERSATIONS + "/" + direct)).hasStatusOk();
	}

	@Test
	void deletedGroupsAreLeftOutOfTheListAndItsCount() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID newest = createGroup(bob, "Newest");
		UUID kept = createGroup(alice, "Kept", bob);
		UUID deleted = createGroup(alice, "Deleted", bob);
		UUID abandoned = createGroup(bob, "Abandoned");
		assertThat(delete(alice, CONVERSATIONS + "/" + deleted)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(post(bob, CONVERSATIONS + "/" + abandoned + "/leave")).hasStatus(HttpStatus.NO_CONTENT);
		// If the deleted groups were listed, they would come after the cursor taken below.
		setTimes(newest, OffsetDateTime.now().minusMinutes(1));
		setTimes(kept, OffsetDateTime.now().minusMinutes(2));
		setTimes(deleted, OffsetDateTime.now().minusMinutes(3));
		setTimes(abandoned, OffsetDateTime.now().minusMinutes(4));

		MvcTestResult offset = get(bob, CONVERSATIONS);
		MvcTestResult cursor = get(bob, CONVERSATIONS + "?size=10&cursor=" + firstCursor(bob, 1));

		assertThat(ids(offset)).containsExactly(newest.toString(), kept.toString());
		assertThat(offset).bodyJson().extractingPath("$.page.totalElements").isEqualTo(2);
		assertThat(ids(cursor)).containsExactly(kept.toString());
	}

	// ---------------------------------------------------------------------------------------------------------
	// Member limit

	@Test
	void aGroupCanHaveUpToTheMemberLimitIncludingItsCreator() {
		TestUser alice = createUser("Alice");
		// The creator's own ID is ignored, so it doesn't use up a place.
		List<UUID> memberIds = new ArrayList<>(createUserIds(ConversationService.MAX_GROUP_MEMBERS - 1));
		memberIds.add(alice.id());

		MvcTestResult result = post(alice, CONVERSATIONS + "/group", new CreateGroupConversationRequest("Full", memberIds));

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.members").asArray()
				.hasSize(ConversationService.MAX_GROUP_MEMBERS);
		assertThat(memberCount(id(result))).isEqualTo(ConversationService.MAX_GROUP_MEMBERS);
	}

	@Test
	void creatingAGroupLargerThanTheLimitIsRejected() {
		TestUser alice = createUser("Alice");
		// Together with the creator, one more than allowed.
		List<UUID> memberIds = createUserIds(ConversationService.MAX_GROUP_MEMBERS);

		MvcTestResult result = post(alice, CONVERSATIONS + "/group", new CreateGroupConversationRequest("Too big", memberIds));

		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail")
				.isEqualTo("A group can have at most " + ConversationService.MAX_GROUP_MEMBERS + " members.");
		assertThat(result).bodyJson().extractingPath("$.maxMembers").isEqualTo(ConversationService.MAX_GROUP_MEMBERS);
		assertThat(conversationsCreatedBy(alice)).isZero();
	}

	@Test
	void membersCanBeAddedUpToTheLimitAndARequestBeyondItAddsNobody() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		List<UUID> initial = new ArrayList<>(createUserIds(ConversationService.MAX_GROUP_MEMBERS - 3));
		initial.add(bob.id());
		UUID group = id(post(alice, CONVERSATIONS + "/group", new CreateGroupConversationRequest("Group", initial)));
		String uri = CONVERSATIONS + "/" + group + "/members";
		assertThat(memberCount(group)).isEqualTo(ConversationService.MAX_GROUP_MEMBERS - 1);

		MvcTestResult twoTooMany = post(alice, uri, new AddMembersRequest(createUserIds(2)));
		assertThat(twoTooMany).hasStatus(HttpStatus.CONFLICT);
		assertThat(twoTooMany).bodyJson().extractingPath("$.maxMembers").isEqualTo(ConversationService.MAX_GROUP_MEMBERS);
		assertThat(memberCount(group)).isEqualTo(ConversationService.MAX_GROUP_MEMBERS - 1);

		// Duplicates and existing members don't count: this adds one user and fills the group.
		UUID last = createUserIds(1).getFirst();
		assertThat(post(alice, uri, new AddMembersRequest(List.of(last, last, bob.id())))).hasStatusOk();
		assertThat(memberCount(group)).isEqualTo(ConversationService.MAX_GROUP_MEMBERS);

		assertThat(post(alice, uri, new AddMembersRequest(createUserIds(1)))).hasStatus(HttpStatus.CONFLICT);
		// Adding someone who is already a member changes nothing, so it still succeeds in a full group.
		assertThat(post(alice, uri, new AddMembersRequest(List.of(bob.id())))).hasStatusOk();
		assertThat(memberCount(group)).isEqualTo(ConversationService.MAX_GROUP_MEMBERS);
	}

	@Test
	void aPlaceFreedByLeavingCanBeTaken() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		List<UUID> initial = new ArrayList<>(createUserIds(ConversationService.MAX_GROUP_MEMBERS - 2));
		initial.add(bob.id());
		UUID group = id(post(alice, CONVERSATIONS + "/group", new CreateGroupConversationRequest("Full", initial)));
		UUID newcomer = createUserIds(1).getFirst();
		String uri = CONVERSATIONS + "/" + group + "/members";

		assertThat(post(alice, uri, new AddMembersRequest(List.of(newcomer)))).hasStatus(HttpStatus.CONFLICT);
		assertThat(post(bob, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(post(alice, uri, new AddMembersRequest(List.of(newcomer)))).hasStatusOk();

		assertThat(memberCount(group)).isEqualTo(ConversationService.MAX_GROUP_MEMBERS);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Cursor pagination

	@Test
	void eachPageCarriesACursorToTheNextOne() {
		TestUser alice = createUser("Alice");
		UUID oldest = createGroup(alice, "Oldest");
		UUID middle = createGroup(alice, "Middle");
		UUID newest = createGroup(alice, "Newest");
		setTimes(oldest, OffsetDateTime.now().minusMinutes(3));
		setTimes(middle, OffsetDateTime.now().minusMinutes(2));
		setTimes(newest, OffsetDateTime.now().minusMinutes(1));

		MvcTestResult first = get(alice, CONVERSATIONS + "?size=2");
		MvcTestResult second = get(alice, CONVERSATIONS + "?size=2&cursor=" + nextCursor(first));

		assertThat(first).bodyJson().extractingPath("$.content[*].id").asArray()
				.containsExactly(newest.toString(), middle.toString());
		assertThat(first).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
		assertThat(second).hasStatusOk();
		assertThat(second).bodyJson().extractingPath("$.content[*].id").asArray().containsExactly(oldest.toString());
		// Page numbers don't apply to a page that starts at a cursor.
		assertThat(second).bodyJson().extractingPath("$.page").isNull();
		assertThat(second).bodyJson().extractingPath("$.nextCursor").isNull();
		// The last page by number has nothing after it either.
		assertThat(get(alice, CONVERSATIONS + "?page=1&size=2")).bodyJson().extractingPath("$.nextCursor").isNull();
	}

	@Test
	void anEmptyCursorIsTheFirstPage() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Group");

		MvcTestResult result = get(alice, CONVERSATIONS + "?cursor=");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.content[*].id").asArray().containsExactly(group.toString());
		assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
	}

	@Test
	void cursorPagesOfConversationsUpdatedAtTheSameMomentAreOrderedById() {
		TestUser alice = createUser("Alice");
		OffsetDateTime sameTime = OffsetDateTime.now().minusMinutes(1);
		List<UUID> groups = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			UUID group = createGroup(alice, "Group " + i);
			setTimes(group, sameTime);
			groups.add(group);
		}
		// PostgreSQL orders UUIDs like their lower-case text, which is how UUID.toString() writes them.
		List<String> expected = groups.stream().map(UUID::toString).sorted(Comparator.reverseOrder()).toList();

		List<String> byCursor = new ArrayList<>();
		String cursor = "";
		do {
			MvcTestResult page = get(alice, CONVERSATIONS + "?size=2&cursor=" + cursor);
			byCursor.addAll(ids(page));
			cursor = nextCursor(page);
		} while (cursor != null);
		List<String> byNumber = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			byNumber.addAll(ids(get(alice, CONVERSATIONS + "?size=2&page=" + page)));
		}

		assertThat(byCursor).containsExactlyElementsOf(expected);
		assertThat(byNumber).containsExactlyElementsOf(expected);
		// Repeating a request gives the same page.
		assertThat(ids(get(alice, CONVERSATIONS + "?size=2&cursor=" + firstCursor(alice, 2))))
				.isEqualTo(ids(get(alice, CONVERSATIONS + "?size=2&cursor=" + firstCursor(alice, 2))))
				.containsExactly(expected.get(2), expected.get(3));
	}

	@Test
	void cursorPagesDoNotShiftWhenConversationsChangeInBetween() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		List<UUID> groups = new ArrayList<>();
		for (int i = 5; i >= 1; i--) {
			UUID group = createGroup(alice, "Group " + i, bob);
			setTimes(group, OffsetDateTime.now().minusMinutes(i));
			groups.add(group);
		}
		// Newest first: g1 was updated a minute ago, g5 five minutes ago.
		UUID g5 = groups.get(0), g4 = groups.get(1), g3 = groups.get(2), g2 = groups.get(3), g1 = groups.get(4);
		MvcTestResult first = get(alice, CONVERSATIONS + "?size=2");
		assertThat(ids(first)).containsExactly(g1.toString(), g2.toString());

		// Before the next page: a new conversation, a change to one already seen, and a deletion further down.
		UUID created = createGroup(alice, "New");
		assertThat(post(alice, CONVERSATIONS + "/" + g1 + "/members/" + bob.id() + "/promote")).hasStatusOk();
		assertThat(delete(alice, CONVERSATIONS + "/" + g4)).hasStatus(HttpStatus.NO_CONTENT);

		MvcTestResult second = get(alice, CONVERSATIONS + "?size=2&cursor=" + nextCursor(first));

		// The cursor continues right after g2: nothing repeats, g4 is gone, and nothing else was skipped.
		assertThat(ids(second)).containsExactly(g3.toString(), g5.toString());
		assertThat(second).bodyJson().extractingPath("$.nextCursor").isNull();
		// The changed and the new conversation are at the start of the list, for the next reload.
		assertThat(ids(get(alice, CONVERSATIONS + "?size=2"))).containsExactly(g1.toString(), created.toString());
		// Page numbers, by contrast, shifted: page 1 now repeats an entry the first page already had.
		assertThat(ids(get(alice, CONVERSATIONS + "?size=2&page=1"))).containsExactly(g2.toString(), g3.toString());
	}

	@Test
	void anInvalidCursorIsABadRequest() {
		TestUser alice = createUser("Alice");
		String notAPosition = Base64.getUrlEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

		for (String cursor : List.of("not-base64!", notAPosition)) {
			MvcTestResult result = get(alice, CONVERSATIONS + "?cursor=" + cursor);

			assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Invalid cursor.");
		}
	}

	@Test
	void pagingThroughAnUnchangingListReturnsEveryConversationExactlyOnce() {
		TestUser alice = createUser("Alice");
		// 53 groups in runs of four with the same activity time, so that page boundaries fall inside runs of equal
		// times. Inserted directly because creating them one request at a time is slow.
		jdbcTemplate.update("""
				insert into conversations (id, type, name, created_by, created_at, updated_at)
				select gen_random_uuid(), 'GROUP', 'Group ' || n, ?, t, t
				from generate_series(1, 53) n,
				     lateral (select timestamptz '2026-10-24 10:00:00Z' - (n / 4) * interval '1 second' as t) times
				""", alice.id());
		jdbcTemplate.update("""
				insert into conversation_members (id, conversation_id, user_id, role, joined_at)
				select gen_random_uuid(), id, created_by, 'ADMIN', created_at from conversations where created_by = ?
				""", alice.id());
		// The expected order, worked out here rather than by the database: most recent activity first, then by ID
		// descending (PostgreSQL orders UUIDs like their lower-case text, which is how UUID.toString() writes them).
		List<String> expected = jdbcTemplate.query("select id, updated_at from conversations where created_by = ?",
						(row, rowNumber) -> Map.entry(row.getObject("updated_at", OffsetDateTime.class).toInstant(),
								row.getObject("id", UUID.class).toString()),
						alice.id())
				.stream()
				.sorted(Map.Entry.<Instant, String>comparingByKey()
						.thenComparing(Map.Entry.<Instant, String>comparingByValue())
						.reversed())
				.map(Map.Entry::getValue)
				.toList();
		assertThat(expected).hasSize(53).doesNotHaveDuplicates();

		for (int size : List.of(1, 4, 6, 20, 100)) {
			List<String> byCursor = new ArrayList<>();
			String cursor = "";
			do {
				MvcTestResult page = get(alice, CONVERSATIONS + "?size=" + size + "&cursor=" + cursor);
				byCursor.addAll(ids(page));
				cursor = nextCursor(page);
			} while (cursor != null && byCursor.size() < 10_000);
			List<String> byNumber = new ArrayList<>();
			for (int page = 0; byNumber.size() < 10_000; page++) {
				List<String> ids = ids(get(alice, CONVERSATIONS + "?size=" + size + "&page=" + page));
				if (ids.isEmpty()) {
					break;
				}
				byNumber.addAll(ids);
			}

			assertThat(byCursor).as("by cursor, size %d", size).containsExactlyElementsOf(expected);
			assertThat(byNumber).as("by number, size %d", size).containsExactlyElementsOf(expected);
		}
	}

	@Test
	void cursorPagesHaveTheSameSizeLimitAsNumberedPages() {
		TestUser alice = createUser("Alice");
		// 102 groups, inserted directly because creating them one request at a time is slow.
		jdbcTemplate.update("""
				insert into conversations (id, type, name, created_by, created_at, updated_at)
				select gen_random_uuid(), 'GROUP', 'Group', ?, now(), now() from generate_series(1, 102)
				""", alice.id());
		jdbcTemplate.update("""
				insert into conversation_members (id, conversation_id, user_id, role, joined_at)
				select gen_random_uuid(), id, created_by, 'ADMIN', created_at from conversations where created_by = ?
				""", alice.id());

		MvcTestResult result = get(alice, CONVERSATIONS + "?size=1000&cursor=" + firstCursor(alice, 1));

		assertThat(result).bodyJson().extractingPath("$.content").asArray().hasSize(100);
		assertThat(result).bodyJson().extractingPath("$.nextCursor").isNotNull();
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

	/** Users who never make requests themselves, for example to fill a group. */
	private List<UUID> createUserIds(int count) {
		return Stream.generate(() -> userService.createUser("user-" + UUID.randomUUID() + "@example.com",
						"{bcrypt}not-a-real-hash", "User").id())
				.limit(count)
				.toList();
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

	private UUID createDirect(TestUser caller, TestUser recipient) {
		MvcTestResult result = post(caller, CONVERSATIONS + "/direct", new CreateDirectConversationRequest(recipient.id()));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return id(result);
	}

	private UUID createGroup(TestUser creator, String name, TestUser... members) {
		MvcTestResult result = post(creator, CONVERSATIONS + "/group",
				new CreateGroupConversationRequest(name, Arrays.stream(members).map(TestUser::id).toList()));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return id(result);
	}

	private MvcTestResult get(TestUser caller, String uri) {
		return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()).exchange();
	}

	private MvcTestResult post(TestUser caller, String uri) {
		return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()).exchange();
	}

	private MvcTestResult post(TestUser caller, String uri, Object body) {
		return postJson(caller, uri, jsonMapper.writeValueAsString(body));
	}

	private MvcTestResult postJson(TestUser caller, String uri, String json) {
		return mvc.post().uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json)
				.exchange();
	}

	private MvcTestResult postAnonymous(String uri, Object body) {
		return mvc.post().uri(uri)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonMapper.writeValueAsString(body))
				.exchange();
	}

	private MvcTestResult put(TestUser caller, String uri, Object body) {
		return putJson(caller, uri, jsonMapper.writeValueAsString(body));
	}

	private MvcTestResult putJson(TestUser caller, String uri, String json) {
		return mvc.put().uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json)
				.exchange();
	}

	private MvcTestResult delete(TestUser caller, String uri) {
		return mvc.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()).exchange();
	}

	/** The path of one member of a conversation. */
	private static String member(UUID conversationId, TestUser user) {
		return CONVERSATIONS + "/" + conversationId + "/members/" + user.id();
	}

	/** The IDs on one page of a conversation list, in order. */
	private List<String> ids(MvcTestResult result) {
		List<String> ids = new ArrayList<>();
		jsonMapper.readTree(body(result)).get("content").forEach(entry -> ids.add(entry.get("id").asString()));
		return ids;
	}

	/** The page's {@code nextCursor}, or {@code null} if it has none. */
	private String nextCursor(MvcTestResult result) {
		assertThat(result).hasStatusOk();
		JsonNode cursor = jsonMapper.readTree(body(result)).get("nextCursor");
		return cursor.isNull() ? null : cursor.asString();
	}

	/** The cursor after the first {@code size} entries of the user's list. */
	private String firstCursor(TestUser user, int size) {
		String cursor = nextCursor(get(user, CONVERSATIONS + "?size=" + size));
		assertThat(cursor).isNotNull();
		return cursor;
	}

	private static void assertUnauthorized(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	private UUID id(MvcTestResult result) {
		return UUID.fromString(jsonMapper.readTree(body(result)).get("id").asString());
	}

	/** The problem detail without its {@code instance}, which repeats the request path. */
	private String withoutInstance(MvcTestResult result) {
		return body(result).replace(result.getRequest().getRequestURI(), "");
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private int count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

	private int memberCount(UUID conversationId) {
		return count("select count(*) from conversation_members where conversation_id = ?", conversationId);
	}

	private int adminCount(UUID conversationId) {
		return count("select count(*) from conversation_members where conversation_id = ? and role = 'ADMIN'",
				conversationId);
	}

	private int conversationsCreatedBy(TestUser user) {
		return count("select count(*) from conversations where created_by = ?", user.id());
	}

	/** The member's role, or {@code null} if the user isn't a member. */
	private String role(UUID conversationId, TestUser user) {
		List<String> roles = jdbcTemplate.queryForList(
				"select role from conversation_members where conversation_id = ? and user_id = ?",
				String.class, conversationId, user.id());
		return roles.isEmpty() ? null : roles.getFirst();
	}

	private OffsetDateTime updatedAt(UUID conversationId) {
		return jdbcTemplate.queryForObject("select updated_at from conversations where id = ?",
				OffsetDateTime.class, conversationId);
	}

	private String name(UUID conversationId) {
		return jdbcTemplate.queryForObject("select name from conversations where id = ?", String.class, conversationId);
	}

	/** Whether the conversation is marked deleted. Fails if its row doesn't exist: deletion keeps it. */
	private boolean isDeleted(UUID conversationId) {
		return jdbcTemplate.queryForObject("select deleted_at is not null from conversations where id = ?",
				Boolean.class, conversationId);
	}

	/**
	 * Sets up a second administrator without going through the promote endpoint, for tests about something
	 * else. The endpoint itself has its own tests.
	 */
	private void makeAdmin(UUID conversationId, TestUser user) {
		jdbcTemplate.update("update conversation_members set role = 'ADMIN' where conversation_id = ? and user_id = ?",
				conversationId, user.id());
	}

	/** Sets when the conversation was created and last updated, to control the list order. */
	private void setTimes(UUID conversationId, OffsetDateTime time) {
		jdbcTemplate.update("update conversations set created_at = ?, updated_at = ? where id = ?",
				time, time, conversationId);
	}

	private void setJoinedAt(UUID conversationId, TestUser user, OffsetDateTime time) {
		jdbcTemplate.update("update conversation_members set joined_at = ? where conversation_id = ? and user_id = ?",
				time, conversationId, user.id());
	}

}
