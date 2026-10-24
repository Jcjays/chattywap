package com.adonis.chattywap.message;

import com.adonis.chattywap.conversation.ConversationNotFoundException;
import com.adonis.chattywap.conversation.ConversationService;
import com.adonis.chattywap.message.dto.ReceiptPositionChangedEvent;
import com.adonis.chattywap.message.dto.ReceiptPositionResponse;
import com.adonis.chattywap.message.dto.ReceiptPositionsResponse;
import com.adonis.chattywap.message.dto.UnreadCountResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * Delivery and read receipts, and unread counts. Every method takes the caller's user ID, which the controller reads
 * from the access token; a member only ever acknowledges for themselves.
 * <ul>
 * <li><b>Sent</b>: the message is stored. Nothing here is needed for that.</li>
 * <li><b>Delivered</b> to a member: their client acknowledged receiving it ({@link #acknowledgeDelivery}). Nothing
 * else counts: not a WebSocket broadcast, a connection or a RabbitMQ publication.</li>
 * <li><b>Read</b> by a member: they reported reading it ({@link #markRead}). Delivery alone never counts as read.</li>
 * </ul>
 * Each member has one delivered and one read position in a conversation, and both are message sequence numbers: an
 * acknowledgement names a message and covers every message up to it, the member's own included. A client acknowledges
 * delivery of the last message up to which it has every message (sequence numbers have no gaps, so it can tell), and
 * reads up to the last message it showed. Positions only move forward, so repeated, retried and outdated
 * acknowledgements are harmless: they change nothing. Reading also moves the delivered position up to the read one.
 * <p>
 * Positions are per member, also in groups: one member's acknowledgement says nothing about the others. Every member
 * of a conversation can see every member's positions, as the conversation's receipts.
 * <p>
 * Every change to a position is announced with a {@link ReceiptPositionChangedEvent}, which the {@code realtime}
 * feature delivers to the members connected to it after the commit. Acknowledgements are synchronous: the position is
 * stored when the request returns, and its state is always available through {@link #listReceiptPositions}, whether
 * or not anybody received the event.
 */
@Service
public class ReceiptService {

	/** The foreign key from a receipt position to its membership. */
	static final String MEMBER_CONSTRAINT = "receipt_positions_member_fkey";

	private final ReceiptPositionRepository receiptPositionRepository;

	private final MessageRepository messageRepository;

	private final UnreadMessageCounts unreadMessageCounts;

	private final ConversationService conversationService;

	private final ApplicationEventPublisher eventPublisher;

	public ReceiptService(ReceiptPositionRepository receiptPositionRepository, MessageRepository messageRepository,
			UnreadMessageCounts unreadMessageCounts, ConversationService conversationService,
			ApplicationEventPublisher eventPublisher) {
		this.receiptPositionRepository = receiptPositionRepository;
		this.messageRepository = messageRepository;
		this.unreadMessageCounts = unreadMessageCounts;
		this.conversationService = conversationService;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * The caller's client has received every message of the conversation up to and including {@code messageId}.
	 * Moves the caller's delivered position there, unless it is already there or further.
	 *
	 * @return the caller's position afterwards
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws MessageNotFoundException if the message isn't one of the conversation's
	 */
	@Transactional
	public ReceiptPositionResponse acknowledgeDelivery(UUID callerId, UUID conversationId, UUID messageId) {
		long sequenceNumber = requireMessage(callerId, conversationId, messageId);
		Instant now = now();
		return advance(callerId, conversationId,
				() -> receiptPositionRepository.advanceDelivered(conversationId, callerId, sequenceNumber, now));
	}

	/**
	 * The caller has read every message of the conversation up to and including {@code messageId}. Moves the caller's
	 * read position there, unless it is already there or further, and the delivered position too where it is behind.
	 *
	 * @return the caller's position afterwards
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 * @throws MessageNotFoundException if the message isn't one of the conversation's
	 */
	@Transactional
	public ReceiptPositionResponse markRead(UUID callerId, UUID conversationId, UUID messageId) {
		long sequenceNumber = requireMessage(callerId, conversationId, messageId);
		Instant now = now();
		return advance(callerId, conversationId,
				() -> receiptPositionRepository.advanceRead(conversationId, callerId, sequenceNumber, now));
	}

	/**
	 * The positions of all current members of the conversation, the caller included.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public ReceiptPositionsResponse listReceiptPositions(UUID callerId, UUID conversationId) {
		Set<UUID> memberIds = conversationService.getMemberIds(callerId, conversationId);
		Map<UUID, ReceiptPosition> positions = receiptPositionRepository.findByConversationId(conversationId).stream()
				.collect(Collectors.toMap(ReceiptPosition::getUserId, Function.identity()));
		List<ReceiptPositionResponse> members = memberIds.stream()
				// As text, the order clients and PostgreSQL see; UUID#compareTo differs from it.
				.sorted(Comparator.comparing(UUID::toString))
				.map(memberId -> positions.containsKey(memberId)
						? toResponse(positions.get(memberId))
						: ReceiptPositionResponse.none(conversationId, memberId))
				.toList();
		return new ReceiptPositionsResponse(members);
	}

	/**
	 * How many messages of the conversation the caller hasn't read: those others sent after the caller's read
	 * position, or all that others sent if the caller hasn't reported reading any.
	 *
	 * @throws ConversationNotFoundException if the conversation doesn't exist or the caller isn't a member
	 */
	@Transactional(readOnly = true)
	public UnreadCountResponse getUnreadCount(UUID callerId, UUID conversationId) {
		conversationService.requireMembership(callerId, conversationId);
		long unreadCount = unreadMessageCounts.countUnread(callerId, List.of(conversationId)).get(conversationId);
		return new UnreadCountResponse(conversationId, unreadCount);
	}

	/**
	 * The message's sequence number. Membership is checked first, so that non-members learn nothing about messages,
	 * not even whether an ID exists.
	 */
	private long requireMessage(UUID callerId, UUID conversationId, UUID messageId) {
		conversationService.requireMembership(callerId, conversationId);
		return messageRepository.findSequenceNumber(conversationId, messageId)
				.orElseThrow(MessageNotFoundException::new);
	}

	/**
	 * Runs the statement that moves a position, then reads the caller's position, and announces it if it moved.
	 * <p>
	 * Membership was checked before, without a lock: a member removed since then, or while the statement waited for
	 * the row, has no membership row any more, and the statement fails on its foreign key. That is the same answer as
	 * for any non-member. (A member removed and added again in between passes: the position lands in the new
	 * membership.)
	 * <p>
	 * No lock is taken, so that acknowledgements never wait for sends or group changes. The cost: the recipients are
	 * read without the conversation lock, and a member removed while this transaction commits can still receive this
	 * one change.
	 */
	private ReceiptPositionResponse advance(UUID callerId, UUID conversationId, IntSupplier statement) {
		boolean moved;
		try {
			moved = statement.getAsInt() > 0;
		}
		catch (DataIntegrityViolationException ex) {
			if (isViolationOf(ex, MEMBER_CONSTRAINT)) {
				throw new ConversationNotFoundException();
			}
			throw ex;
		}
		// Not loaded before in this transaction, so it is read from the database, with the statement's change.
		ReceiptPositionResponse position = receiptPositionRepository
				.findById(new ReceiptPosition.Key(conversationId, callerId))
				.map(ReceiptService::toResponse)
				.orElseThrow(() -> new IllegalStateException("The receipt position just written does not exist"));
		if (moved) {
			eventPublisher.publishEvent(new ReceiptPositionChangedEvent(position,
					conversationService.getMemberIds(callerId, conversationId)));
		}
		return position;
	}

	private static ReceiptPositionResponse toResponse(ReceiptPosition position) {
		return new ReceiptPositionResponse(position.getConversationId(), position.getUserId(),
				position.getLastDeliveredSequenceNumber(), position.getDeliveredAt(), position.getLastReadSequenceNumber(),
				position.getReadAt());
	}

	private static boolean isViolationOf(DataIntegrityViolationException ex, String constraintName) {
		return ex.getCause() instanceof ConstraintViolationException violation
				&& constraintName.equals(violation.getConstraintName());
	}

	/**
	 * PostgreSQL stores microseconds. Truncating keeps the times in a response identical to the ones read back from
	 * the database later.
	 */
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
