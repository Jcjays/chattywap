package com.adonis.chattywap.conversation.dto;

import com.adonis.chattywap.common.PageMetadata;

import java.util.List;

/**
 * One page of the caller's conversation list. Without a cursor, the JSON is the same as Spring Data's
 * {@code PagedModel} ({@code content} and {@code page}), plus {@code nextCursor}.
 *
 * @param page       offset pagination metadata; {@code null} when the page was requested with a cursor, where page
 *                   numbers don't apply
 * @param nextCursor pass as {@code cursor} to get the entries after this page; {@code null} if there are none
 */
public record ConversationPageResponse(
		List<ConversationSummaryResponse> content,
		PageMetadata page,
		String nextCursor) {
}
