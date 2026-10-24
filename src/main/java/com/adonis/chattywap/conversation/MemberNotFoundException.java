package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The user to remove isn't a member of the conversation. Only thrown to members, who can see the member
 * list anyway.
 */
public class MemberNotFoundException extends ErrorResponseException {

	public MemberNotFoundException() {
		super(HttpStatus.NOT_FOUND,
				ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The user is not a member of this conversation."),
				null);
	}

}
