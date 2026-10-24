package com.adonis.chattywap.conversation;

/**
 * One row of a user's conversation list, as {@link ConversationRepository#findPageByMember} returns it.
 *
 * @param role        the listing user's role in the conversation
 * @param memberCount how many members the conversation has
 */
record ConversationListRow(Conversation conversation, MemberRole role, long memberCount) {
}
