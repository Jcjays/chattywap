package com.adonis.chattywap.conversation;

public enum ConversationType {

	/** Exactly two members, unique per pair of users, no member management. */
	DIRECT,

	/**
	 * A named conversation of up to {@link ConversationService#MAX_GROUP_MEMBERS} members, managed by its
	 * administrators, that can be deleted.
	 */
	GROUP

}
