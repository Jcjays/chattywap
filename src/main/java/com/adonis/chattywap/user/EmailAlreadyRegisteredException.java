package com.adonis.chattywap.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Extends Spring's {@link ErrorResponseException}, which carries an HTTP status and a problem-detail body.
 * The exception handler in {@code common} turns every such exception into a response, without knowing this class.
 */
public class EmailAlreadyRegisteredException extends ErrorResponseException {

	public EmailAlreadyRegisteredException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email address already exists."),
				null);
	}

}
