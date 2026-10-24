package com.adonis.chattywap.message.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Acknowledges every message of the conversation up to and including one: as received (delivery) or as read. The
 * member isn't part of the request: it is always the caller.
 *
 * @param messageId a message of the conversation the request is for
 */
public record ReceiptRequest(@NotNull UUID messageId) {
}
