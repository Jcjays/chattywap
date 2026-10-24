package com.adonis.chattywap.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown for an unknown email and for a wrong password alike, with the same message, so the response
 * doesn't reveal whether an account exists.
 */
class InvalidCredentialsException extends ErrorResponseException {

	InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED,
				ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password."),
				null);
	}

}
