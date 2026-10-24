package com.adonis.chattywap.message;

import com.adonis.chattywap.common.InvalidCursorException;
import com.adonis.chattywap.common.KeysetCursor;
import com.adonis.chattywap.common.PageMetadata;
import com.adonis.chattywap.conversation.ConversationNotFoundException;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.MessagePageResponse;
import com.adonis.chattywap.message.dto.MessageResponse;
import com.adonis.chattywap.user.UserService;
import com.adonis.chattywap.user.dto.UserSummary;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * A conversation's history is ordered by {@code createdAt}, newest first, then by ID. The server sets
 * {@code createdAt} when it stores the message, shortly before the transaction commits. Messages stored in the same
 * microsecond are ordered by their random IDs: the order is stable, but not necessarily the order in which they
 * were sent. And a message whose commit takes longer than a later message's becomes visible after that one, already
 * sorted below it.
 */
@Service
public class MessageService {

	private final MessageRepository messageRepository;

	private final ConversationService conversationService;

	private final UserService userService;

	public MessageService(MessageRepository messageRepository, ConversationService conversationService,
			UserService userService) {
		this.messageRepository = messageRepository;
		this.conversationService = conversationService;
		this.userService = userService;
	}

	/**
	 * Stores a message from the caller. The membership check and the insert happen in one transaction, with the
	 * caller's membership locked in between: a message is never stored after its sender left or was removed, or
	 * after the group was deleted. The message is committed when this method returns.
	 *
	 * @param content valid as message content (see {@code SendMessageRequest}); stored as it is
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional
	public MessageResponse sendMessage(UUID callerId, UUID conversationId, String content) {
		conversationService.lockMembership(callerId, conversationId);
		Message message = messageRepository.save(new Message(conversationId, callerId, content, now()));
		return toResponses(List.of(message)).getFirst();
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
	 * arrived. New messages sort before the cursor; the client gets them by loading the first page again. That
	 * includes a message that committed late with a time newer than the cursor's (see the class comment): it sorts
	 * into a page the client already has, so only reloading that part of the history shows it.
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
						requireSender(senders, message.getSenderId()), message.getContent(), message.getCreatedAt()))
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

	/**
	 * PostgreSQL stores microseconds. Truncating keeps the time in the response identical to the one read back
	 * from the database later, and in cursors.
	 */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
