package com.adonis.chattywap.conversation;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.auth.dto.LoginRequest;
import com.adonis.chattywap.auth.dto.RegisterRequest;
import com.adonis.chattywap.auth.dto.TokenResponse;
import com.adonis.chattywap.conversation.dto.AddMembersRequest;
import com.adonis.chattywap.conversation.dto.CreateDirectConversationRequest;
import com.adonis.chattywap.conversation.dto.CreateGroupConversationRequest;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
	void whenTheLastMemberLeavesTheGroupStaysWithoutMembers() {
		TestUser alice = createUser("Alice");
		UUID group = createGroup(alice, "Group");

		assertThat(post(alice, CONVERSATIONS + "/" + group + "/leave")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(memberCount(group)).isZero();
		assertThat(count("select count(*) from conversations where id = ?", group)).isEqualTo(1);
		assertThat(get(alice, CONVERSATIONS + "/" + group)).hasStatus(HttpStatus.NOT_FOUND);
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

	private MvcTestResult delete(TestUser caller, String uri) {
		return mvc.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken()).exchange();
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

	/** There is no endpoint for promoting members yet, so tests that need a second administrator set it here. */
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
