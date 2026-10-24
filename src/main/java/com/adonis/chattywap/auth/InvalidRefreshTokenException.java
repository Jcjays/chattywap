package com.adonis.chattywap.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * One error for unknown, expired, already used and revoked refresh tokens. The client's next step is the
 * same in every case: log in again.
 */
class InvalidRefreshTokenException extends ErrorResponseException {

	InvalidRefreshTokenException() {
		super(HttpStatus.UNAUTHORIZED,
				ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid, expired or revoked refresh token."),
				null);
	}

}
