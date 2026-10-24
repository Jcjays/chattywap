package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The group's only administrator tried to demote themselves, which would leave the group without one.
 */
public class LastGroupAdminException extends ErrorResponseException {

	public LastGroupAdminException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
						"A group needs an administrator. Promote another member before demoting the last administrator."),
				null);
	}

}
