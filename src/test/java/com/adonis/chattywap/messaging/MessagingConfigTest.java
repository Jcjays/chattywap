package com.adonis.chattywap.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which listener failures are retried. The container wraps every exception from a listener in a
 * {@link ListenerExecutionFailedException}, so the decision looks at the causes.
 */
class MessagingConfigTest {

	@Test
	void failuresThatMayGoAwayAreRetried() {
		assertThat(MessagingConfig.isRetryable(wrapped(new CannotAcquireLockException("serialization failure"))))
				.isTrue();
		assertThat(MessagingConfig.isRetryable(wrapped(new DataAccessResourceFailureException("database down"))))
				.isTrue();
		assertThat(MessagingConfig.isRetryable(wrapped(new IllegalStateException("unexpected")))).isTrue();
	}

	@Test
	void failuresThatWouldRepeatAreNotRetried() {
		assertThat(MessagingConfig.isRetryable(wrapped(new InvalidEventException("missing fields")))).isFalse();
		assertThat(MessagingConfig.isRetryable(wrapped(new MessageConversionException("unreadable")))).isFalse();
		assertThat(MessagingConfig.isRetryable(wrapped(new DataIntegrityViolationException("unknown conversation"))))
				.isFalse();
	}

	private static ListenerExecutionFailedException wrapped(Throwable cause) {
		return new ListenerExecutionFailedException("Listener threw exception", cause);
	}

}
