package com.adonis.chattywap.conversation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationCursorTest {

	@Test
	void decodingAnEncodedCursorGivesTheSamePosition() {
		// Microseconds, the precision PostgreSQL stores.
		ConversationCursor cursor = new ConversationCursor(Instant.parse("2026-10-24T10:25:00.654321Z"), UUID.randomUUID());

		assertThat(ConversationCursor.decode(cursor.encode())).isEqualTo(cursor);
	}

	@Test
	void encodedCursorsNeedNoEscapingInAQueryString() {
		ConversationCursor cursor = new ConversationCursor(Instant.now(), UUID.randomUUID());

		assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
	}

	@Test
	void stringsTheServerDidNotIssueAreRejected() {
		List<String> invalid = List.of(
				"",
				"not base64!",
				encode("no separator"),
				encode("yesterday," + UUID.randomUUID()),
				encode("2026-10-24T10:25:00Z,not-a-uuid"),
				encode("2026-10-24T10:25:00Z,"));

		for (String value : invalid) {
			assertThatThrownBy(() -> ConversationCursor.decode(value))
					.as(value)
					.isInstanceOf(InvalidCursorException.class);
		}
	}

	private static String encode(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

}
