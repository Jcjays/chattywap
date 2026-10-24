package com.adonis.chattywap.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * A position in a list ordered by a time, then by a unique ID: the sort key of the last entry a page contained.
 * The next page starts with the first entry that sorts after this position. Used for lists where entries are
 * added, changed or removed while a client pages through them, such as a user's conversations or a
 * conversation's messages.
 * <p>
 * The position is a pair of values, not a row, so it stays valid when that entry changes or disappears. Clients
 * get it as an opaque string: URL-safe Base64 of {@code <time>,<id>}.
 */
public record KeysetCursor(Instant time, UUID id) {

	private static final char SEPARATOR = ',';

	public String encode() {
		String text = time.toString() + SEPARATOR + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * @throws InvalidCursorException if {@code value} isn't a string {@link #encode} produced
	 */
	public static KeysetCursor decode(String value) {
		try {
			String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separator = text.indexOf(SEPARATOR);
			if (separator < 0) {
				throw new InvalidCursorException();
			}
			return new KeysetCursor(Instant.parse(text.substring(0, separator)),
					UUID.fromString(text.substring(separator + 1)));
		}
		catch (IllegalArgumentException | DateTimeParseException ex) {
			throw new InvalidCursorException();
		}
	}

}
