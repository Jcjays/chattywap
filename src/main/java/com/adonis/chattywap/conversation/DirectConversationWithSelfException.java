package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DirectConversationWithSelfException extends ErrorResponseException {

	public DirectConversationWithSelfException() {
		super(HttpStatus.BAD_REQUEST,
				ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
						"A direct conversation needs another user as recipient."),
				null);
	}

}
