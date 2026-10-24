package com.adonis.chattywap.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The {@code cursor} of a list request isn't one the server issued (see {@link KeysetCursor}).
 */
public class InvalidCursorException extends ErrorResponseException {

	public InvalidCursorException() {
		super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid cursor."), null);
	}

}
