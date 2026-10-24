package com.adonis.chattywap.message;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when a request names a message that isn't one of the conversation's, whether it doesn't exist or belongs to
 * another conversation, with the same message: the response doesn't reveal messages of other conversations.
 */
public class MessageNotFoundException extends ErrorResponseException {

	public MessageNotFoundException() {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Message not found."), null);
	}

}
