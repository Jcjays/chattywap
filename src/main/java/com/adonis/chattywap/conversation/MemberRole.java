package com.adonis.chattywap.conversation;

/**
 * A member's role in a conversation. Roles only matter in group conversations; both members of a direct
 * conversation are {@link #MEMBER}s.
 */
public enum MemberRole {

	/** Manages the group: adds and removes members, promotes and demotes administrators, renames and deletes it. */
	ADMIN,

	MEMBER

}
