package com.adonis.chattywap.messaging;

import com.adonis.chattywap.TestcontainersConfiguration;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.MessageService;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.messaging.dto.MessageCreatedEvent;
import com.adonis.chattywap.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transactional outbox's write side: a stored message and its {@code MessageCreated} event commit together, or
 * neither does. Failures are provoked with PostgreSQL triggers that reject one conversation's rows. Nothing is
 * published here: the test profile turns the relay off.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutboxIT {

	@Autowired
	private MessageService messageService;

	@Autowired
	private ConversationService conversationService;

	@Autowired
	private UserService userService;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void aStoredMessageHasExactlyOnePendingMessageCreatedEvent() {
		UUID alice = createUser();
		UUID bob = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of(bob)).id();

		MessageResponse message = messageService.sendMessage(bob, group, "Secret plans");

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"select * from outbox_events where payload::jsonb ->> 'messageId' = ?", message.id().toString());
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.getFirst();
		assertThat(row).containsEntry("event_type", "MessageCreated").containsEntry("event_version", 1)
				.containsEntry("routing_key", "message.created").containsEntry("attempts", 0)
				.containsEntry("published_at", null).containsEntry("last_error", null);
		// Due at once.
		assertThat(row.get("next_attempt_at")).isEqualTo(row.get("created_at"));

		JsonNode payload = jsonMapper.readTree((String) row.get("payload"));
		assertThat(payload.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "eventVersion",
				"occurredAt", "messageId", "conversationId", "senderId");
		assertThat(payload.get("eventId").asString()).isEqualTo(row.get("id").toString());
		assertThat(payload.get("eventType").asString()).isEqualTo("MessageCreated");
		assertThat(payload.get("eventVersion").asInt()).isEqualTo(1);
		assertThat(Instant.parse(payload.get("occurredAt").asString())).isEqualTo(message.createdAt());
		assertThat(payload.get("conversationId").asString()).isEqualTo(group.toString());
		assertThat(payload.get("senderId").asString()).isEqualTo(bob.toString());
		// Neither the content nor anything else about the sender.
		assertThat((String) row.get("payload")).doesNotContain("Secret plans").doesNotContain("@example.com");
		// It reads back as the event contract.
		assertThat(jsonMapper.readValue((String) row.get("payload"), MessageCreatedEvent.class).messageId())
				.isEqualTo(message.id());
	}

	@Test
	void everyMessageGetsItsOwnEvent() {
		UUID alice = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();

		messageService.sendMessage(alice, group, "One");
		messageService.sendMessage(alice, group, "Two");

		assertThat(eventCount(group)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject(
				"select count(distinct id) from outbox_events where payload::jsonb ->> 'conversationId' = ?",
				Integer.class, group.toString())).isEqualTo(2);
	}

	@Test
	void aFailureToWriteTheEventRollsBackTheMessage() {
		UUID alice = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();
		Instant activityBefore = updatedAt(group);

		whileWritingEventsFails(group, () -> assertThatThrownBy(() -> messageService.sendMessage(alice, group, "Lost"))
				.hasMessageContaining("simulated outbox failure"));

		assertThat(messageCount(group)).isZero();
		assertThat(eventCount(group)).isZero();
		assertThat(updatedAt(group)).isEqualTo(activityBefore);
	}

	@Test
	void aMessageThatCannotBeStoredLeavesNoEvent() {
		UUID alice = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();

		// Fails at commit, after the event has been written in the same transaction.
		whileStoringMessagesFails(group, () -> assertThatThrownBy(() -> messageService.sendMessage(alice, group, "Lost"))
				.rootCause().hasMessageContaining("simulated failure"));

		assertThat(messageCount(group)).isZero();
		assertThat(eventCount(group)).isZero();
	}

	@Test
	void writingAnEventNeedsThePublishersTransaction() {
		UUID alice = createUser();
		UUID group = conversationService.createGroupConversation(alice, "Group", List.of()).id();
		MessageResponse message = messageService.sendMessage(alice, group, "Hi");

		// Outside a transaction, the event would be written whether or not the change it announces is stored.
		assertThatThrownBy(() -> eventPublisher.publishEvent(new MessageSentEvent(message, Set.of(alice))))
				.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(eventCount(group)).isEqualTo(1);
	}

	/** Makes PostgreSQL reject every outbox event about the conversation while {@code action} runs. */
	private void whileWritingEventsFails(UUID conversationId, Runnable action) {
		jdbcTemplate.execute("""
				create function simulated_outbox_failure() returns trigger language plpgsql as $$
				begin
					raise exception 'simulated outbox failure';
				end
				$$
				""");
		jdbcTemplate.execute("create trigger simulated_outbox_failure before insert on outbox_events for each row "
				+ "when (new.payload::jsonb ->> 'conversationId' = '" + conversationId + "') "
				+ "execute function simulated_outbox_failure()");
		try {
			action.run();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_outbox_failure on outbox_events");
			jdbcTemplate.execute("drop function simulated_outbox_failure()");
		}
	}

	/**
	 * Makes PostgreSQL reject every message stored in the conversation while {@code action} runs. The trigger is
	 * deferred: it fails the transaction when it commits, after all its statements ran.
	 */
	private void whileStoringMessagesFails(UUID conversationId, Runnable action) {
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
			action.run();
		}
		finally {
			jdbcTemplate.execute("drop trigger simulated_failure on messages");
			jdbcTemplate.execute("drop function simulated_failure()");
		}
	}

	private UUID createUser() {
		return userService.createUser("user-" + UUID.randomUUID() + "@example.com", "{bcrypt}not-a-real-hash", "User")
				.id();
	}

	private int eventCount(UUID conversationId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from outbox_events where payload::jsonb ->> 'conversationId' = ?", Integer.class,
				conversationId.toString());
	}

	private int messageCount(UUID conversationId) {
		return jdbcTemplate.queryForObject("select count(*) from messages where conversation_id = ?", Integer.class,
				conversationId);
	}

	private Instant updatedAt(UUID conversationId) {
		return jdbcTemplate.queryForObject("select updated_at from conversations where id = ?", OffsetDateTime.class,
				conversationId).toInstant();
	}

}
