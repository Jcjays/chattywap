package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A member who isn't an administrator tried to add or remove someone. Only thrown to members: non-members get
 * {@link ConversationNotFoundException}.
 */
public class NotGroupAdminException extends ErrorResponseException {

	public NotGroupAdminException() {
		super(HttpStatus.FORBIDDEN,
				ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
						"Only group administrators can add or remove other members."),
				null);
	}

}
