package com.adonis.chattywap.message;

import com.adonis.chattywap.conversation.UnreadMessageCounter;
import com.adonis.chattywap.message.dto.UnreadCountResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counts unread messages in PostgreSQL, for one conversation ({@link ReceiptService#getUnreadCount}) and for a page
 * of the conversation list ({@code ConversationService}) alike, so the two always agree.
 * <p>
 * A member's unread messages are those that others sent after the member's read position; the member's own messages
 * never count. Without a read position, every message others sent counts. Nothing is stored for counting: the counts
 * are read from the messages and the read positions, so they can't drift from them, whatever order messages and read
 * updates commit in.
 * <p>
 * Depends on repositories only, not on {@code ConversationService}, which uses it: callers check membership.
 */
@Component
class UnreadMessageCounts implements UnreadMessageCounter {

	private final ReceiptPositionRepository receiptPositionRepository;

	private final MessageRepository messageRepository;

	UnreadMessageCounts(ReceiptPositionRepository receiptPositionRepository, MessageRepository messageRepository) {
		this.receiptPositionRepository = receiptPositionRepository;
		this.messageRepository = messageRepository;
	}

	/**
	 * Two statements however many conversations: one for those where the user has a read position, one for the
	 * others.
	 */
	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Long> countUnread(UUID userId, Collection<UUID> conversationIds) {
		if (conversationIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Long> counts = new HashMap<>();
		receiptPositionRepository.countUnreadAfterReadPositions(userId, conversationIds)
				.forEach(count -> counts.put(count.conversationId(), count.unreadCount()));
		List<UUID> withoutReadPosition = conversationIds.stream().filter(id -> !counts.containsKey(id)).toList();
		if (!withoutReadPosition.isEmpty()) {
			for (UnreadCountResponse count : messageRepository.countFromOthers(userId, withoutReadPosition)) {
				counts.put(count.conversationId(), count.unreadCount());
			}
		}
		conversationIds.forEach(id -> counts.putIfAbsent(id, 0L));
		return counts;
	}

}
