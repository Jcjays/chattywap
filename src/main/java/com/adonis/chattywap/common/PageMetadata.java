package com.adonis.chattywap.common;

/**
 * Offset pagination metadata of a list response, the same JSON as the {@code page} of Spring Data's
 * {@code PagedModel}.
 *
 * @param size          the requested page size, after capping
 * @param number        zero-based page number
 * @param totalElements entries in the whole list
 * @param totalPages    pages of {@code size} entries in the whole list
 */
public record PageMetadata(long size, long number, long totalElements, long totalPages) {
}
