package com.adonis.chattywap.message.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Published by {@code ReceiptService} when a member's delivered or read position moved forward, inside the transaction
 * that moved it. Acknowledgements that change nothing, such as a repeated or an outdated one, publish nothing.
 * Listeners that deliver it must wait for that transaction to commit ({@code @TransactionalEventListener}): if it
 * rolls back, the position didn't move.
 *
 * @param position     the member's position after the change
 * @param recipientIds the conversation's members, the member included: who may receive it. Read after the change,
 *                     without the conversation lock, so a member removed while the change commits can still be among
 *                     them; every message the position covers was sent before that removal.
 */
public record ReceiptPositionChangedEvent(ReceiptPositionResponse position, Set<UUID> recipientIds) {
}
