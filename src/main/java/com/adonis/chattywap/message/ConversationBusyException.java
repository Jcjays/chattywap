package com.adonis.chattywap.message;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A message couldn't be stored in time because so many messages are being sent to the same conversation that it would
 * have to wait longer than the send's time limit ({@link ConversationSendGate}). Nothing was stored; sending again
 * later may succeed, so it is {@code 503}, like other temporary unavailability.
 */
public class ConversationBusyException extends ErrorResponseException {

	public ConversationBusyException() {
		super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Too many messages are being sent to this conversation. Try again later."), null);
	}

}
