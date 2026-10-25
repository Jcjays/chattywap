package com.adonis.chattywap.messaging;

import com.adonis.chattywap.common.LogContext;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * The log context of a consumed event, while a consumer, or the recoverer that dead-letters it, works on it: the event
 * ID (the message ID) as {@link LogContext#EVENT_ID}, and the ID of the request that wrote the event (the correlation
 * ID, see {@link OutboxRelay}) as {@link LogContext#REQUEST_ID}. The consumer's log lines can then be found with the
 * request's ID as well as with the event's. Values that don't look like IDs are left out.
 */
final class EventLogContext {

	private EventLogContext() {
	}

	static void set(Message message) {
		MessageProperties properties = message.getMessageProperties();
		if (LogContext.isValidRequestId(properties.getMessageId())) {
			MDC.put(LogContext.EVENT_ID, properties.getMessageId());
		}
		if (LogContext.isValidRequestId(properties.getCorrelationId())) {
			MDC.put(LogContext.REQUEST_ID, properties.getCorrelationId());
		}
	}

	static void clear() {
		MDC.remove(LogContext.EVENT_ID);
		MDC.remove(LogContext.REQUEST_ID);
	}

}
