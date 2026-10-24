package com.adonis.chattywap.message;

import com.adonis.chattywap.common.InvalidCursorException;
import com.adonis.chattywap.common.KeysetCursor;
import com.adonis.chattywap.common.PageMetadata;
import com.adonis.chattywap.conversation.ConversationNotFoundException;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.MessagePageResponse;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.message.dto.MessageSentEvent;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserSummary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point of the {@code message} feature. Every method takes the caller's user ID, which the controller reads
 * from the access token.
 * <p>
 * Only members of a conversation can post to it and read its history. To everyone else, the conversation doesn't
 * exist: they get the same 404 as for an unknown ID, decided by {@link ConversationService}. Members who leave or
 * are removed lose access to the whole history, including their own messages; the messages stay, with their
 * sender, for the remaining members.
 * <p>
 * A conversation's history is ordered by {@code createdAt}, newest first, then by ID, which makes the order total
 * and the same on every request. {@code createdAt} is a timestamp, not a sequence number: the time
 * {@link ConversationService#recordActivity} returns. Messages of one conversation are stored one after the other,
 * under the conversation lock, and that time is read after the lock is taken, so with one application clock that
 * doesn't go back, a message that commits later has a later time: the history order is the commit order. With
 * several application instances whose clocks differ, or a clock that is set back, it isn't guaranteed: a message
 * that commits later can sort below one that was already visible. Messages stored in the same microsecond are
 * ordered by their random IDs.
 * <p>
 * Every message also has a sequence number in its conversation: 1 for the first, one more for each following one,
 * without gaps. It is assigned under the same lock, so it follows the commit order on any number of instances, whatever
 * their clocks say. Read and delivery receipts are positions in this order ({@link ReceiptService}); the history keeps
 * its order by time.
 * <p>
 * Every stored message is announced with a {@link MessageSentEvent}, which the {@code realtime} feature delivers to
 * connected members once the transaction has committed. Both ways of sending, REST and STOMP, go through
 * {@link #sendMessage}, so each message is announced exactly once.
 */
@Service
public class MessageService {

	private final MessageRepository messageRepository;

	private final ConversationService conversationService;

	private final UserService userService;

	private final ApplicationEventPublisher eventPublisher;

	public MessageService(MessageRepository messageRepository, ConversationService conversationService,
			UserService userService, ApplicationEventPublisher eventPublisher) {
		this.messageRepository = messageRepository;
		this.conversationService = conversationService;
		this.userService = userService;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Stores a message from the caller and records it as the conversation's latest activity, which moves the
	 * conversation to the top of its members' lists. Both happen in one transaction, committed when this method
	 * returns: if the message can't be stored, the conversation's activity time doesn't change either.
	 * <p>
	 * The conversation stays locked from the membership check to the commit, so a message is never stored after
	 * its sender left or was removed, or after the group was deleted. Messages to the same conversation are stored
	 * one after the other, each with the next sequence number.
	 * <p>
	 * Publishes a {@link MessageSentEvent} with the members at that moment as recipients. Listeners deliver it only
	 * if the transaction commits, so a message that couldn't be stored is never delivered.
	 *
	 * @param content valid as message content (see {@code SendMessageRequest}); stored as it is
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional
	public MessageResponse sendMessage(UUID callerId, UUID conversationId, String content) {
		Instant createdAt = conversationService.recordActivity(callerId, conversationId);
		// Read under the conversation lock that recordActivity took: nobody can join or leave until the commit.
		Set<UUID> recipientIds = conversationService.getMemberIds(callerId, conversationId);
		// Also under the lock: no other message of the conversation can be stored before this one commits, so the next
		// number is free, and a message that commits later gets a higher one.
		long sequenceNumber = messageRepository.findLastSequenceNumber(conversationId) + 1;
		Message message = messageRepository.save(
				new Message(conversationId, sequenceNumber, callerId, content, createdAt));
		MessageResponse response = toResponses(List.of(message)).getFirst();
		eventPublisher.publishEvent(new MessageSentEvent(response, recipientIds));
		return response;
	}

	/**
	 * One page of the conversation's history by page number, newest message first. A new message shifts all
	 * following pages by one; the response's {@code nextCursor} continues with {@link #listMessagesAfter}, which
	 * doesn't have that problem.
	 *
	 * @param page zero-based page number
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public MessagePageResponse listMessages(UUID callerId, UUID conversationId, int page, int size) {
		conversationService.requireMembership(callerId, conversationId);
		PageRequest pageRequest = PageRequest.of(page, size);
		// Spring Data JPA refuses offsets beyond Integer.MAX_VALUE. Such a page lies far past the end of any
		// history, so it gets the same answer as every page past the end: no messages.
		Page<Message> messages = pageRequest.getOffset() > Integer.MAX_VALUE
				? new PageImpl<>(List.of(), pageRequest, messageRepository.countByConversation(conversationId))
				: messageRepository.findPageByConversation(conversationId, pageRequest);

		String nextCursor = messages.hasNext() && messages.hasContent()
				? cursorAfter(messages.getContent().getLast())
				: null;
		return new MessagePageResponse(toResponses(messages.getContent()),
				new PageMetadata(messages.getSize(), messages.getNumber(), messages.getTotalElements(),
						messages.getTotalPages()),
				nextCursor);
	}

	/**
	 * The conversation's messages that come after {@code cursor} in the history's order (see
	 * {@link #listMessages}), that is the older ones, at most {@code size} of them.
	 * <p>
	 * A cursor is a position in the history, not an offset, so messages sent while the client pages back through
	 * the history don't shift the following pages: no message appears twice, and none is skipped because others
	 * arrived. New messages sort before the cursor; the client gets them by loading the first page again. Where the
	 * history order isn't the commit order (see the class comment), a message can commit with a time newer than the
	 * cursor's after the client passed that point; only reloading that part of the history shows it.
	 *
	 * @param cursor a {@code nextCursor} from an earlier response
	 * @throws InvalidCursorException if the cursor isn't one the server issued
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public MessagePageResponse listMessagesAfter(UUID callerId, UUID conversationId, String cursor, int size) {
		KeysetCursor position = KeysetCursor.decode(cursor);
		conversationService.requireMembership(callerId, conversationId);
		// One more than requested tells whether another page follows, without a count query.
		List<Message> messages = messageRepository.findByConversationAfter(conversationId, position.time(),
				position.id(), Limit.of(size + 1));

		boolean hasNext = messages.size() > size;
		List<Message> page = hasNext ? messages.subList(0, size) : messages;
		String nextCursor = hasNext ? cursorAfter(page.getLast()) : null;
		return new MessagePageResponse(toResponses(page), null, nextCursor);
	}

	private List<MessageResponse> toResponses(List<Message> messages) {
		// The senders of all messages on the page, looked up in one query.
		Set<UUID> senderIds = messages.stream().map(Message::getSenderId).collect(Collectors.toSet());
		Map<UUID, UserSummary> senders = userService.findUserSummaries(senderIds);
		return messages.stream()
				.map(message -> new MessageResponse(message.getId(), message.getConversationId(),
						message.getSequenceNumber(), requireSender(senders, message.getSenderId()), message.getContent(),
						message.getCreatedAt()))
				.toList();
	}

	/**
	 * Senders' user rows can't be deleted (foreign key with {@code on delete restrict}), so a miss is a bug.
	 */
	private static UserSummary requireSender(Map<UUID, UserSummary> senders, UUID senderId) {
		UserSummary sender = senders.get(senderId);
		if (sender == null) {
			throw new IllegalStateException("Sender " + senderId + " of a message does not exist");
		}
		return sender;
	}

	/** The history position right after {@code message}: its sort key, {@code (createdAt, id)}. */
	private static String cursorAfter(Message message) {
		return new KeysetCursor(message.getCreatedAt(), message.getId()).encode();
	}

}
