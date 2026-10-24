package com.adonis.chattywap.conversation;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Counts a member's unread messages, for the conversation list. The {@code message} feature, which owns messages and
 * read positions, implements it. It is declared here because {@code conversation} can't depend on {@code message},
 * which depends on {@code conversation}: {@link ConversationService} only knows this interface.
 */
public interface UnreadMessageCounter {

	/**
	 * The unread count of each of the conversations, as {@code GET .../unread-count} reports it for each one alone.
	 * Doesn't check membership: callers pass conversations the user is a member of.
	 *
	 * @return an entry for every conversation in {@code conversationIds}, 0 for those without unread messages
	 */
	Map<UUID, Long> countUnread(UUID userId, Collection<UUID> conversationIds);

}
