package com.adonis.chattywap.message.dto;

import com.adonis.chattywap.common.PageMetadata;

import java.util.List;

/**
 * One page of a conversation's history, newest message first. The same shape as the conversation list: without a
 * cursor, the JSON is the same as Spring Data's {@code PagedModel} ({@code content} and {@code page}), plus
 * {@code nextCursor}.
 *
 * @param page       offset pagination metadata; {@code null} when the page was requested with a cursor, where page
 *                   numbers don't apply
 * @param nextCursor pass as {@code cursor} to get the older messages after this page; {@code null} if there are
 *                   none
 */
public record MessagePageResponse(
		List<MessageResponse> content,
		PageMetadata page,
		String nextCursor) {
}
