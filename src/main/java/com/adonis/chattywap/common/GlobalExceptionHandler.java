package com.adonis.chattywap.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions thrown while handling a request into RFC 9457 problem-detail responses
 * ({@code application/problem+json}), so every error has the same shape:
 * <pre>
 * {"type": "about:blank", "title": "Conflict", "status": 409, "detail": "...", "instance": "/api/v1/..."}
 * </pre>
 * The base class already handles Spring MVC's own exceptions (malformed JSON, unsupported method, ...) and
 * every {@link org.springframework.web.ErrorResponseException}, which the feature exceptions extend. That is
 * why this class doesn't need to know any feature.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Bean Validation failures on a {@code @Valid @RequestBody}. Lists each invalid field with its message, without
	 * the rejected value (see {@link FieldViolation}).
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
		problem.setProperty("errors", FieldViolation.of(ex.getBindingResult()));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/**
	 * Anything not handled above is a bug or an infrastructure failure. The client gets a generic message;
	 * the details go to the server log only.
	 */
	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unexpected error while handling a request", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
	}

}
