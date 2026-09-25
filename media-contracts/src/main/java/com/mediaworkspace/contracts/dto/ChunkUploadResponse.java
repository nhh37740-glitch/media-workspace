package com.mediaworkspace.contracts.dto;

/**
 * Result of {@code PUT /uploads/{id}/chunks/{index}}.
 *
 * <p>The first accepted upload of an index returns 201; an identical re-upload, concurrent or
 * sequential, returns 200 with the stored values. Different bytes for an index already stored
 * return 409 and the stored chunk is left untouched.
 *
 * @param index     chunk index, from 0
 * @param sha256    hash recorded for this chunk
 * @param sizeBytes stored size in bytes
 */
public record ChunkUploadResponse(int index, String sha256, long sizeBytes) {
}
