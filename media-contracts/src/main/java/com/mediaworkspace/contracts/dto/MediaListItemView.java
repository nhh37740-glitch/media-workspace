package com.mediaworkspace.contracts.dto;

/**
 * One row of {@code GET /spaces/{id}/media}.
 *
 * @param mediaId    identifier
 * @param title      user-facing title
 * @param status     PROCESSING, READY, FAILED or CANCELLED
 * @param taskId     the media's processing task
 * @param durationMs media duration once known, otherwise {@code null}
 * @param width      output width once known, otherwise {@code null}
 * @param height     output height once known, otherwise {@code null}
 * @param createdAt  creation instant, UTC ISO-8601
 */
public record MediaListItemView(
        String mediaId,
        String title,
        String status,
        String taskId,
        Long durationMs,
        Integer width,
        Integer height,
        String createdAt) {
}
