package com.adonis.chattywap.message.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Published by {@code MessageService} for every stored message, whether it was sent through REST or STOMP, inside
 * the transaction that stores it. Listeners that deliver the message must wait for that transaction to commit
 * ({@code @TransactionalEventListener}): if it rolls back, the message was never stored.
 *
 * @param message      the stored message, the same as the API returns it
 * @param recipientIds the members of the conversation when the message was stored, the sender included: who may
 *                     receive it. Read under the conversation lock, so nobody who left or was removed before the
 *                     message was stored is among them.
 */
public record MessageSentEvent(MessageResponse message, Set<UUID> recipientIds) {
}
