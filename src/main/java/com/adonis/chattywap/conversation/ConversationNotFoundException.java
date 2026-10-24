package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when the conversation doesn't exist and when the caller isn't a member of it alike, with the same
 * message, so the response doesn't reveal whether a conversation the caller can't see exists.
 */
public class ConversationNotFoundException extends ErrorResponseException {

	public ConversationNotFoundException() {
		super(HttpStatus.NOT_FOUND,
				ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Conversation not found."),
				null);
	}

}
