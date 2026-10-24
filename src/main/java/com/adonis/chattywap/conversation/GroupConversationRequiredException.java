package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Adding, removing and leaving are only possible in group conversations. 409: the request is fine in itself
 * but conflicts with the type of the conversation it targets.
 */
public class GroupConversationRequiredException extends ErrorResponseException {

	public GroupConversationRequiredException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
						"Members can only be added, removed or leave in group conversations."),
				null);
	}

}
