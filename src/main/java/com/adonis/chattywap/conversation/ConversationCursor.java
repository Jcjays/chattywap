package com.adonis.chattywap.conversation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * A position in a user's conversation list: the sort key of the last entry a page contained. The list is ordered
 * by {@code updatedAt} descending, then by ID descending, and the next page starts with the first entry that
 * sorts after this position.
 * <p>
 * The position is a pair of values, not a row, so it stays valid when that conversation changes, is left or is
 * deleted. Clients get it as an opaque string: URL-safe Base64 of {@code <updatedAt>,<id>}.
 */
record ConversationCursor(Instant updatedAt, UUID id) {

	private static final char SEPARATOR = ',';

	/** The position right after {@code conversation}. */
	static ConversationCursor after(Conversation conversation) {
		return new ConversationCursor(conversation.getUpdatedAt(), conversation.getId());
	}

	String encode() {
		String text = updatedAt.toString() + SEPARATOR + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @throws InvalidCursorException if {@code value} isn't a string {@link #encode} produced
	 */
	static ConversationCursor decode(String value) {
		try {
			String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separator = text.indexOf(SEPARATOR);
			if (separator < 0) {
				throw new InvalidCursorException();
			}
			return new ConversationCursor(Instant.parse(text.substring(0, separator)),
					UUID.fromString(text.substring(separator + 1)));
		}
		catch (IllegalArgumentException | DateTimeParseException ex) {
			throw new InvalidCursorException();
		}
	}

}
