package com.mediaworkspace.application.model;

/**
 * Search parameters for a media listing.
 *
 * @param workspaceId space to search in
 * @param titleQuery  substring to match against the title, or {@code null} for all rows; the
 *                    persistence adapter escapes LIKE metacharacters so user input cannot widen
 *                    the match
 * @param page        1-based page number
 * @param pageSize    1..100
 */
public record MediaQuery(String workspaceId, String titleQuery, int page, int pageSize) {

    public int offset() {
        return (page - 1) * pageSize;
    }
}
