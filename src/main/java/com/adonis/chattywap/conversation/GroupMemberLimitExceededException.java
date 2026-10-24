package com.adonis.chattywap.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Creating a group or adding members would take the group past {@link ConversationService#MAX_GROUP_MEMBERS}.
 * Nobody is added. The problem detail states the limit in {@code maxMembers}.
 * <p>
 * 409 when creating a group too, so that clients get one status for one rule: the request is well-formed, but
 * the group it would produce isn't allowed.
 */
public class GroupMemberLimitExceededException extends ErrorResponseException {

	public GroupMemberLimitExceededException(int maxMembers) {
		super(HttpStatus.CONFLICT, problem(maxMembers), null);
	}

	private static ProblemDetail problem(int maxMembers) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"A group can have at most " + maxMembers + " members.");
		problem.setProperty("maxMembers", maxMembers);
		return problem;
	}

}
