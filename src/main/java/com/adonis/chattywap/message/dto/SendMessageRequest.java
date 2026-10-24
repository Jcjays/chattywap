package com.adonis.chattywap.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The sender isn't part of the request: it is always the caller.
 *
 * @param content stored and returned exactly as sent, surrounding whitespace included. Must contain a character
 *                that isn't whitespace. At most {@value #MAX_CONTENT_LENGTH} UTF-16 code units, so characters
 *                outside the Basic Multilingual Plane, such as most emoji, count as two. Can't contain the NUL
 *                character, which PostgreSQL can't store, or half of a surrogate pair, which isn't valid Unicode
 *                and would be stored as {@code ?}. (The pattern works on code points: a complete pair is one code
 *                point outside the surrogate range, so only unpaired surrogates match the excluded range.)
 */
public record SendMessageRequest(
		@NotBlank
		@Size(max = MAX_CONTENT_LENGTH)
		@Pattern(regexp = "[^\\x00\\uD800-\\uDFFF]*", message = "must not contain NUL characters or unpaired surrogates")
		String content) {

	public static final int MAX_CONTENT_LENGTH = 4000;

}
