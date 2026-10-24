package com.adonis.chattywap.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

class PasswordTooLongException extends ErrorResponseException {

	PasswordTooLongException(int maxBytes) {
		super(HttpStatus.BAD_REQUEST,
				ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
						"Password must not be longer than " + maxBytes + " bytes when UTF-8 encoded."),
				null);
	}

}
