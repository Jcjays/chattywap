package com.adonis.chattywap.common;

import org.springframework.validation.BindingResult;

import java.util.Comparator;
import java.util.List;

/**
 * One invalid field of a request, as listed in the {@code errors} of a validation problem detail. Used for REST
 * request bodies and STOMP message payloads alike, so both report validation errors the same way.
 * <p>
 * The rejected value is left out on purpose: it could be a password.
 */
public record FieldViolation(String field, String message) {

	/**
	 * The field errors of a failed validation, ordered by field and message so the response is the same every time.
	 */
	public static List<FieldViolation> of(BindingResult bindingResult) {
		return bindingResult.getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
				.sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
				.toList();
	}

}
