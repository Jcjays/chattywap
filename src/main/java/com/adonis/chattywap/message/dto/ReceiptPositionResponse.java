package com.adonis.chattywap.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * How far one member of a conversation has acknowledged its messages. Positions are message sequence numbers
 * ({@link MessageResponse#sequenceNumber()}) and cover every message up to them: a message with sequence number
 * {@code n} is read by the member if {@code n <= lastReadSequenceNumber}, and delivered to them if
 * {@code n <= lastDeliveredSequenceNumber}. Reading implies delivery, so the read position is never ahead of the
 * delivered one.
 *
 * @param lastDeliveredSequenceNumber the last message the member's client acknowledged receiving, with every message
 *                                    before it; 0 if none
 * @param deliveredAt                 when the delivered position last moved forward; {@code null} if it never did.
 *                                    Never earlier than the time before.
 * @param lastReadSequenceNumber      the last message the member reported reading, with every message before it; 0 if
 *                                    none
 * @param readAt                      when the read position last moved forward; {@code null} if it never did. Never
 *                                    earlier than the time before, nor than the delivered time when it moved.
 */
public record ReceiptPositionResponse(
		UUID conversationId,
		UUID userId,
		long lastDeliveredSequenceNumber,
		Instant deliveredAt,
		long lastReadSequenceNumber,
		Instant readAt) {

	/** The position of a member who hasn't acknowledged anything. */
	public static ReceiptPositionResponse none(UUID conversationId, UUID userId) {
		return new ReceiptPositionResponse(conversationId, userId, 0, null, 0, null);
	}

}
