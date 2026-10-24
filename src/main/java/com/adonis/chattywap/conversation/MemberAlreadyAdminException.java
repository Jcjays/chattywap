package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The member to promote is already an administrator. 409: promoting is an explicit change of role, not a request
 * for a state, so promoting twice is reported instead of ignored.
 */
public class MemberAlreadyAdminException extends ErrorResponseException {

	public MemberAlreadyAdminException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The user is already an administrator of this group."),
				null);
	}

}
