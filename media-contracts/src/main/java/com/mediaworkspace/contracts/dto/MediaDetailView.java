package com.mediaworkspace.contracts.dto;

/**
 * Result of {@code GET /media/{id}}.
 *
 * <p>Deliberately has no {@code storageKey}: internal storage layout is not part of the public
 * representation.
 *
 * @param mediaId          identifier
 * @param title            user-facing title
 * @param status           PROCESSING, READY, FAILED or CANCELLED
 * @param taskId           the media's processing task
 * @param originalFilename display name of the uploaded file
 * @param sizeBytes        size of the stored original
 * @param durationMs       media duration once known, otherwise {@code null}
 * @param width            output width once known, otherwise {@code null}
 * @param height           output height once known, otherwise {@code null}
 * @param errorCode        failure code when the task failed terminally, otherwise {@code null}
 * @param version          optimistic lock version, required by {@code PATCH /media/{id}}
 * @param createdAt        creation instant, UTC ISO-8601
 */
public record MediaDetailView(
        String mediaId,
        String title,
        String status,
        String taskId,
        String originalFilename,
        long sizeBytes,
        Long durationMs,
        Integer width,
        Integer height,
        String errorCode,
        long version,
        String createdAt) {
}
