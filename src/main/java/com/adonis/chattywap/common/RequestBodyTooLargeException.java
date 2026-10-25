package com.adonis.chattywap.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.io.IOException;

/**
 * Reading a request body went past the limit of {@link RequestBodyLimitFilter}. An {@link IOException}, because it is
 * thrown while the body is read; Spring MVC reports it wrapped in an {@code HttpMessageNotReadableException}, which
 * {@code GlobalExceptionHandler} answers with 413.
 */
public class RequestBodyTooLargeException extends IOException {

	private final long maxBytes;

	RequestBodyTooLargeException(long maxBytes) {
		super("The request body is larger than " + maxBytes + " bytes");
		this.maxBytes = maxBytes;
	}

	ProblemDetail toProblem() {
		return problem(maxBytes);
	}

	static ProblemDetail problem(long maxBytes) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
				"The request body is larger than " + maxBytes + " bytes.");
		problem.setProperty("maxBytes", maxBytes);
		return problem;
	}

}
