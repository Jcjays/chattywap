package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The request names users that don't exist. The problem detail lists them in {@code userIds}.
 */
public class UnknownUsersException extends ErrorResponseException {

	public UnknownUsersException(Collection<UUID> unknownUserIds) {
		super(HttpStatus.BAD_REQUEST, problem(unknownUserIds), null);
	}

	private static ProblemDetail problem(Collection<UUID> unknownUserIds) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Some users do not exist.");
		List<String> sortedIds = unknownUserIds.stream().map(UUID::toString).sorted().toList();
		problem.setProperty("userIds", sortedIds);
		return problem;
	}

}
