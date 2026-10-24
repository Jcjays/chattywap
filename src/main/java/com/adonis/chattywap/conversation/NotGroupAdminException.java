package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A member who isn't an administrator tried something only administrators may do: add or remove someone, change
 * roles, rename or delete the group. Only thrown to members: non-members get {@link ConversationNotFoundException}.
 */
public class NotGroupAdminException extends ErrorResponseException {

	/** For adding and removing others. */
	public NotGroupAdminException() {
		this("Only group administrators can add or remove other members.");
	}

	public NotGroupAdminException(String detail) {
		super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail), null);
	}

}
