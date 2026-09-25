package com.mediaworkspace.contracts.dto;

import java.util.List;

/**
 * Uniform list envelope. Paging starts at page 1, {@code pageSize} is 1..100 and ordering is
 * fixed to {@code createdAt DESC, id DESC} so repeated reads are stable.
 *
 * @param items    the page contents
 * @param page     1-based page number
 * @param pageSize requested page size
 * @param total    total number of matching rows, not the size of {@code items}
 */
public record PageResponse<T>(List<T> items, int page, int pageSize, long total) {

    public static <T> PageResponse<T> of(List<T> items, int page, int pageSize, long total) {
        return new PageResponse<>(List.copyOf(items), page, pageSize, total);
    }
}
