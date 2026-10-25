package com.adonis.chattywap.common;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The keys of the values every log line can carry in its diagnostic context (SLF4J's MDC), so that an operator can
 * follow one operation through REST, STOMP, the database and asynchronous processing. Structured log formats (see
 * README) write them as fields of their own; the plain-text format prints the request ID.
 * <ul>
 * <li>{@value #REQUEST_ID}: one HTTP request or one STOMP frame, set by {@link RequestLoggingFilter} and
 * {@code realtime}. The events a request writes to the outbox keep it as their correlation ID, and the consumers that
 * process those events log it again, so an asynchronous effect leads back to the request that caused it.</li>
 * <li>{@value #USER_ID}: the authenticated caller.</li>
 * <li>{@value #EVENT_ID}: the event a consumer is processing.</li>
 * </ul>
 * Only IDs go into the context, never credentials, tokens or message content.
 */
public final class LogContext {

	public static final String REQUEST_ID = "requestId";

	public static final String USER_ID = "userId";

	public static final String EVENT_ID = "eventId";

	/**
	 * What a request ID taken from elsewhere (a proxy's header, a message property) must look like before it is used:
	 * short, and nothing that could forge or break a log line.
	 */
	private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

	private LogContext() {
	}

	public static String newRequestId() {
		return UUID.randomUUID().toString();
	}

	public static boolean isValidRequestId(String value) {
		return value != null && VALID_REQUEST_ID.matcher(value).matches();
	}

}
