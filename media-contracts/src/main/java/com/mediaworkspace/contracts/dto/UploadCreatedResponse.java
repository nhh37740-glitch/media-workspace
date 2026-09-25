package com.mediaworkspace.contracts.dto;

/**
 * Result of creating an upload session. The client needs only these values to slice and send the
 * file; it never chooses a chunk size or a chunk count.
 *
 * @param uploadId   session identifier
 * @param chunkSize  byte size of every chunk except possibly the last
 * @param chunkCount total number of chunks, derived from the declared size
 * @param status     {@code OPEN} on creation
 * @param expiresAt  instant after which an untouched OPEN session is expired
 */
public record UploadCreatedResponse(
        String uploadId,
        int chunkSize,
        int chunkCount,
        String status,
        String expiresAt) {
}
