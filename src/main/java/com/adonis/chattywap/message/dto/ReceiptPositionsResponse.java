package com.adonis.chattywap.message.dto;

import java.util.List;

/**
 * The receipt positions of a conversation's members.
 *
 * @param members one entry per current member, the caller included, ordered by user ID; members who haven't
 *                acknowledged anything have positions of 0
 */
public record ReceiptPositionsResponse(List<ReceiptPositionResponse> members) {
}
