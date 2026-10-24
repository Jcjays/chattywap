package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Member management, roles, renaming and deletion are only possible in group conversations. 409: the request is
 * fine in itself but conflicts with the type of the conversation it targets.
 */
public class GroupConversationRequiredException extends ErrorResponseException {

	/** For adding, removing and leaving. */
	public GroupConversationRequiredException() {
		this("Members can only be added, removed or leave in group conversations.");
	}

	public GroupConversationRequiredException(String detail) {
		super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), null);
	}

}
