package com.adonis.chattywap.conversation;

/**
 * A member's role in a conversation. Roles only matter in group conversations; both members of a direct
 * conversation are {@link #MEMBER}s.
 */
public enum MemberRole {

	/** May add and remove members of the group. */
	ADMIN,

	MEMBER

}
