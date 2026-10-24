package com.adonis.chattywap.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysetCursorTest {

	@Test
	void decodingAnEncodedCursorGivesTheSamePosition() {
		// Microseconds, the precision PostgreSQL stores.
		KeysetCursor cursor = new KeysetCursor(Instant.parse("2026-10-24T10:25:00.654321Z"), UUID.randomUUID());

		assertThat(KeysetCursor.decode(cursor.encode())).isEqualTo(cursor);
	}

	@Test
	void encodedCursorsNeedNoEscapingInAQueryString() {
		KeysetCursor cursor = new KeysetCursor(Instant.now(), UUID.randomUUID());

		assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
	}

	@Test
	void theEncodingIsStable() {
		// Clients may hold cursors issued before the cursor moved to this class; they must stay valid.
		KeysetCursor cursor = new KeysetCursor(Instant.parse("2026-10-24T10:25:00.654321Z"),
				UUID.fromString("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"));

		assertThat(cursor.encode()).isEqualTo(encode("2026-10-24T10:25:00.654321Z,7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"));
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
			assertThatThrownBy(() -> KeysetCursor.decode(value))
					.as(value)
					.isInstanceOf(InvalidCursorException.class);
		}
	}

	private static String encode(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

}
