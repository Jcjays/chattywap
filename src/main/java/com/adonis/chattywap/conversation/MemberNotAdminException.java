package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The member to demote isn't an administrator.
 */
public class MemberNotAdminException extends ErrorResponseException {

	public MemberNotAdminException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The user is not an administrator of this group."),
				null);
	}

}
