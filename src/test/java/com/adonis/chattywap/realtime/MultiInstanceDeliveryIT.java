package com.adonis.chattywap.realtime;

import com.adonis.chattywap.ChattywapApplication;
import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.ReceiptRequest;
import com.adonis.chattywap.message.dto.SendMessageRequest;
import com.adonis.chattywap.realtime.StompTestClient.Frame;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two application instances sharing PostgreSQL and RabbitMQ, as behind a load balancer, over real HTTP and WebSocket
 * connections: a message or receipt change stored through one instance reaches its recipients' connections on both,
 * each once and in the order sent, and a subscription that outlived its membership receives nothing, wherever it is
 * connected. While RabbitMQ is down, each instance still delivers to its own clients, the other instance's clients
 * don't receive those deliveries (they are in the history), and once it is back, delivery between the instances
 * resumes without anything arriving late (ARCHITECTURE.md, decision 26).
 * <p>
 * The instances are started as applications of their own, not through Spring's test support, which runs one
 * application per test class, and use this class's containers, which no other test uses: stopping RabbitMQ reaches
 * no other test. Like the other STOMP tests, these show that something did <em>not</em> arrive with a marker message
 * sent afterwards, never by sleeping.
 */
class MultiInstanceDeliveryIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static PostgreSQLContainer postgres;

	private static RabbitMQContainer rabbitMq;

	private static Instance a;

	private static Instance b;

	@BeforeAll
	static void startInstances() {
		postgres = TestcontainersConfiguration.postgres();
		rabbitMq = TestcontainersConfiguration.rabbitMq();
		Startables.deepStart(postgres, rabbitMq).join();
		a = Instance.start(postgres, rabbitMq);
		b = Instance.start(postgres, rabbitMq);
	}

	@AfterAll
	static void stopInstances() {
		for (Instance instance : Arrays.asList(b, a)) {
			if (instance != null) {
				instance.context().close();
			}
		}
		for (AutoCloseable container : Arrays.asList(rabbitMq, postgres)) {
			if (container != null) {
				try {
					container.close();
				}
				catch (Exception ex) {
					// Testcontainers removes it at the latest when the JVM ends.
				}
			}
		}
	}

	@Test
	void aMessageStoredThroughOneInstanceReachesEveryRecipientConnectionOnBothInstancesOnce() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		TestUser carol = createUser("Carol");
		UUID group = createGroup(alice, bob, carol);
		UUID direct = a.bean(ConversationService.class).createDirectConversation(alice.id(), carol.id())
				.conversation().id();

		// Bob is connected to both instances, as from two devices.
		try (StompTestClient aliceOnA = subscribed(a, alice, group);
				StompTestClient bobOnA = subscribed(a, bob, group);
				StompTestClient bobOnB = subscribed(b, bob, group);
				StompTestClient carolOnB = StompTestClient.connected(b.port(), carol.accessToken())) {
			carolOnB.subscribe("messages", topic(group));
			carolOnB.subscribe("direct", topic(direct));
			carolOnB.sync();
			// Carol keeps her subscription on B, but isn't a recipient any more. Removed through B, sent through A:
			// the recipients are the members when the message is stored, wherever that happens.
			b.bean(ConversationService.class).removeMember(alice.id(), group, carol.id());

			UUID hello = restSend(a, alice, group, "Hello");
			restSend(a, alice, group, "Marker from A");
			restSend(a, alice, direct, "Direct marker");

			// On B, through RabbitMQ: the stored message, as the history has it, once (a second copy would come
			// before the marker, which A published after it).
			JsonNode toBobOnB = json(bobOnB.nextMessage("messages"));
			assertThat(toBobOnB.get("id").asString()).isEqualTo(hello.toString());
			assertThat(toBobOnB).isEqualTo(historyEntry(b, bob, group, hello));
			assertThat(content(bobOnB.nextMessage("messages"))).isEqualTo("Marker from A");
			// Carol, removed, received neither.
			assertThat(content(carolOnB.nextMessage("direct"))).isEqualTo("Direct marker");

			// On A, directly.
			for (StompTestClient onA : List.of(aliceOnA, bobOnA)) {
				assertThat(json(onA.nextMessage("messages"))).isEqualTo(toBobOnB);
				assertThat(content(onA.nextMessage("messages"))).isEqualTo("Marker from A");
			}

			// A receives its own messages back from RabbitMQ and skips them: its clients' next message is the next
			// one sent, here through B (A's copies were queued for A before B stored it).
			restSend(b, bob, group, "Reply from B");
			assertThat(content(aliceOnA.nextMessage("messages"))).isEqualTo("Reply from B");
			assertThat(content(bobOnA.nextMessage("messages"))).isEqualTo("Reply from B");
			assertThat(content(bobOnB.nextMessage("messages"))).isEqualTo("Reply from B");
		}
	}

	@Test
	void oneConnectionsMessagesReachTheOtherInstanceInTheOrderSent() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient aliceOnA = subscribed(a, alice, group);
				StompTestClient bobOnB = subscribed(b, bob, group)) {
			List<String> sent = IntStream.rangeClosed(1, 50).mapToObj(i -> "Message " + i).toList();
			sent.forEach(content -> aliceOnA.sendJson(send(group), sendRequest(content)));

			List<String> received = new ArrayList<>();
			List<Long> sequenceNumbers = new ArrayList<>();
			for (int i = 0; i < sent.size(); i++) {
				JsonNode message = json(bobOnB.nextMessage("messages"));
				received.add(message.get("content").asString());
				sequenceNumbers.add(message.get("sequenceNumber").asLong());
			}
			assertThat(received).containsExactlyElementsOf(sent);
			assertThat(sequenceNumbers).isSorted().doesNotHaveDuplicates();
		}
	}

	@Test
	void receiptChangesReachTheMembersConnectedToTheOtherInstance() {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);
		UUID message = restSend(a, alice, group, "Read me");

		try (StompTestClient aliceOnA = StompTestClient.connected(a.port(), alice.accessToken());
				StompTestClient bobOnB = StompTestClient.connected(b.port(), bob.accessToken())) {
			aliceOnA.subscribe("receipts", receipts(group));
			aliceOnA.sync();
			bobOnB.subscribe("receipts", receipts(group));
			bobOnB.sync();

			JsonNode stored = JSON.readTree(b.http().post()
					.uri("/api/v1/conversations/{id}/receipts/read", group)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + bob.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(JSON.writeValueAsString(new ReceiptRequest(message)))
					.retrieve()
					.body(String.class));

			// Stored through B: Alice, on A, receives it through RabbitMQ, Bob's own connection directly.
			assertThat(json(aliceOnA.nextMessage("receipts"))).isEqualTo(stored);
			assertThat(json(bobOnB.nextMessage("receipts"))).isEqualTo(stored);
			assertThat(stored.get("userId").asString()).isEqualTo(bob.id().toString());
			assertThat(stored.get("lastReadSequenceNumber").asLong()).isEqualTo(1);
		}
	}

	@Test
	void whileRabbitMqIsDownEachInstanceDeliversOnlyToItsOwnClientsAndAfterwardsToBothAgain() throws Exception {
		TestUser alice = createUser("Alice");
		TestUser bob = createUser("Bob");
		UUID group = createGroup(alice, bob);

		try (StompTestClient aliceOnA = subscribed(a, alice, group);
				StompTestClient bobOnB = subscribed(b, bob, group)) {
			double droppedOnA = dropped(a);
			double droppedOnB = dropped(b);
			rabbitmqctl("stop_app");
			try {
				// Storing, and delivering to the instance's own clients, don't involve RabbitMQ.
				restSend(a, alice, group, "From A during the outage");
				assertThat(content(aliceOnA.nextMessage("messages"))).isEqualTo("From A during the outage");
				bobOnB.sendJson(send(group), sendRequest("From B during the outage"));
				assertThat(content(bobOnB.nextMessage("messages"))).isEqualTo("From B during the outage");

				// Neither reaches the other instance: each instance drops what it can't publish.
				await().atMost(TIMEOUT).until(() -> dropped(a) > droppedOnA && dropped(b) > droppedOnB);
				// The other instance's clients find them in the history.
				assertThat(historyContents(b, bob, group)).contains("From A during the outage");
				assertThat(historyContents(a, alice, group)).contains("From B during the outage");
				assertThat(aliceOnA.isOpen()).isTrue();
				assertThat(bobOnB.isOpen()).isTrue();
			}
			finally {
				rabbitmqctl("start_app");
			}

			// Once RabbitMQ is back, both instances declare their queues again and consume them.
			await().atMost(TIMEOUT).ignoreExceptions().until(() -> consumesItsQueue(a) && consumesItsQueue(b));

			// The next message each client receives is the next one sent: what was dropped never arrives late.
			restSend(a, alice, group, "From A afterwards");
			assertThat(content(bobOnB.nextMessage("messages"))).isEqualTo("From A afterwards");
			assertThat(content(aliceOnA.nextMessage("messages"))).isEqualTo("From A afterwards");
			bobOnB.sendJson(send(group), sendRequest("From B afterwards"));
			assertThat(content(aliceOnA.nextMessage("messages"))).isEqualTo("From B afterwards");
			assertThat(content(bobOnB.nextMessage("messages"))).isEqualTo("From B afterwards");
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers

	/**
	 * One running application.
	 *
	 * @param port the port of its HTTP and WebSocket server
	 */
	private record Instance(ConfigurableApplicationContext context, int port) {

		/**
		 * Starts the application with the test profile, on free ports, against these containers. Command-line
		 * arguments, because they take precedence over application.yaml and a local .env file.
		 */
		static Instance start(PostgreSQLContainer postgres, RabbitMQContainer rabbitMq) {
			ConfigurableApplicationContext context = new SpringApplicationBuilder(ChattywapApplication.class)
					.profiles("test")
					.initializers(Instance::excludeTestConfigurations)
					.run("--server.port=0",
							"--spring.datasource.url=" + postgres.getJdbcUrl(),
							"--spring.datasource.username=" + postgres.getUsername(),
							"--spring.datasource.password=" + postgres.getPassword(),
							"--spring.rabbitmq.host=" + rabbitMq.getHost(),
							"--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
							"--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
							"--spring.rabbitmq.password=" + rabbitMq.getAdminPassword());
			return new Instance(context,
					context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
		}

		/**
		 * Keeps {@link TestcontainersConfiguration}, which the application's component scanning finds on the test
		 * class path, out of the application: its containers would replace the shared ones. Spring's test support
		 * does the same for the applications it starts.
		 */
		private static void excludeTestConfigurations(ConfigurableApplicationContext context) {
			context.getBeanFactory().registerSingleton(TestConfigurationExcludeFilter.class.getName(),
					new TestConfigurationExcludeFilter());
		}

		<T> T bean(Class<T> type) {
			return context.getBean(type);
		}

		RestClient http() {
			return RestClient.create("http://localhost:" + port);
		}

	}

	private static final class TestConfigurationExcludeFilter extends TypeExcludeFilter {

		@Override
		public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
			return metadataReader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof TestConfigurationExcludeFilter;
		}

		@Override
		public int hashCode() {
			return TestConfigurationExcludeFilter.class.hashCode();
		}

	}

	private record TestUser(UUID id, String accessToken) {
	}

	private static TestUser createUser(String displayName) {
		UserResponse user = a.bean(UserService.class).createUser("user-" + UUID.randomUUID() + "@example.com",
				"{bcrypt}not-a-real-hash", displayName);
		return new TestUser(user.id(), accessToken(user.id()));
	}

	/** Signed with the key both instances share, with the claims the application issues: valid on either. */
	private static String accessToken(UUID userId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(a.context().getEnvironment().getRequiredProperty("chattywap.auth.jwt-issuer"))
				.audience(List.of(a.context().getEnvironment().getRequiredProperty("chattywap.auth.jwt-audience")))
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return a.bean(JwtEncoder.class).encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	private static UUID createGroup(TestUser creator, TestUser... members) {
		return a.bean(ConversationService.class).createGroupConversation(creator.id(), "Group",
				Arrays.stream(members).map(TestUser::id).toList()).id();
	}

	/** A connection to the instance, subscribed to the conversation's messages as {@code "messages"}. */
	private static StompTestClient subscribed(Instance instance, TestUser user, UUID conversationId) {
		StompTestClient client = StompTestClient.connected(instance.port(), user.accessToken());
		client.subscribe("messages", topic(conversationId));
		client.sync();
		return client;
	}

	/** Sends through the instance's REST API and returns the stored message's ID. */
	private static UUID restSend(Instance instance, TestUser caller, UUID conversationId, String content) {
		String created = instance.http().post()
				.uri("/api/v1/conversations/{id}/messages", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(sendRequest(content))
				.retrieve()
				.body(String.class);
		return UUID.fromString(JSON.readTree(created).get("id").asString());
	}

	/** The conversation's history, newest first, read through the instance's REST API. */
	private static List<JsonNode> history(Instance instance, TestUser caller, UUID conversationId) {
		String page = instance.http().get()
				.uri("/api/v1/conversations/{id}/messages?size=100", conversationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.accessToken())
				.retrieve()
				.body(String.class);
		List<JsonNode> messages = new ArrayList<>();
		JSON.readTree(page).get("content").forEach(messages::add);
		return messages;
	}

	private static JsonNode historyEntry(Instance instance, TestUser caller, UUID conversationId, UUID messageId) {
		return history(instance, caller, conversationId).stream()
				.filter(message -> message.get("id").asString().equals(messageId.toString()))
				.findFirst()
				.orElseThrow();
	}

	private static List<String> historyContents(Instance instance, TestUser caller, UUID conversationId) {
		return history(instance, caller, conversationId).stream()
				.map(message -> message.get("content").asString())
				.toList();
	}

	/** Live deliveries the instance dropped instead of passing them on to the other, for any reason. */
	private static double dropped(Instance instance) {
		return instance.bean(MeterRegistry.class).find("chattywap.fanout.dropped").counters().stream()
				.mapToDouble(Counter::count)
				.sum();
	}

	/** Whether the instance's fan-out queue exists and the instance consumes it. */
	private static boolean consumesItsQueue(Instance instance) {
		String queue = instance.context().getBean("realtimeFanoutQueue", Queue.class).getName();
		QueueInformation information = instance.bean(AmqpAdmin.class).getQueueInfo(queue);
		return information != null && information.getConsumerCount() == 1;
	}

	private static void rabbitmqctl(String command) throws Exception {
		ExecResult result = rabbitMq.execInContainer("rabbitmqctl", command);
		assertThat(result.getExitCode()).as("rabbitmqctl %s: %s", command, result.getStderr()).isZero();
	}

	private static String topic(UUID conversationId) {
		return "/topic/conversations/" + conversationId + "/messages";
	}

	private static String receipts(UUID conversationId) {
		return "/topic/conversations/" + conversationId + "/receipts";
	}

	private static String send(UUID conversationId) {
		return "/app/conversations/" + conversationId + "/messages";
	}

	private static String sendRequest(String content) {
		return JSON.writeValueAsString(new SendMessageRequest(content));
	}

	private static JsonNode json(Frame frame) {
		return JSON.readTree(frame.body());
	}

	private static String content(Frame message) {
		return json(message).get("content").asString();
	}

}
