package com.mediaworkspace.application.model;

import com.mediaworkspace.contracts.model.MediaState;

import java.time.Instant;

/**
 * A media row. {@code deletedAt} shadows the row: every read path must filter it out, which the
 * repository queries do.
 *
 * @param sourceKey  storage-relative path of the immutable original
 * @param outputKey  storage-relative path of the published MP4, or {@code null} before success
 * @param posterKey  storage-relative path of the published poster, or {@code null}
 * @param version    optimistic lock version used by {@code PATCH /media/{id}}
 */
public record MediaRecord(
        String id,
        String workspaceId,
        String uploaderId,
        String title,
        String originalFilename,
        String sourceKey,
        long sourceSize,
        String sourceHash,
        MediaState status,
        long version,
        Long durationMs,
        Integer width,
        Integer height,
        String outputKey,
        String posterKey,
        Instant deletedAt,
        Instant createdAt) {
}
