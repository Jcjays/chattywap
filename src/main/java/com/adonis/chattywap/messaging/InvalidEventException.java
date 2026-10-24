package com.adonis.chattywap.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * A message that isn't a valid event: unreadable JSON, missing fields, another event type or an unknown version.
 * Processing it again would fail the same way, so it isn't retried: the container rejects it, and the broker moves
 * it to the dead-letter queue.
 */
class InvalidEventException extends AmqpRejectAndDontRequeueException {

	InvalidEventException(String message) {
		super(message);
	}

}
