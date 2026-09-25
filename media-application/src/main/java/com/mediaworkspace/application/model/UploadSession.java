package com.mediaworkspace.application.model;

import com.mediaworkspace.contracts.model.UploadState;

import java.time.Instant;

/**
 * An upload session row.
 *
 * @param quotaReserved  whether this session still holds a source-quota reservation; guards
 *                       against releasing the same reservation twice
 * @param leaseUntil     deadline of the merge lease, {@code null} when no finalizer holds one
 * @param nextFinalizeAt earliest instant a finalizer may pick the session up again
 * @param errorCode      failure code when the state is FAILED
 */
public record UploadSession(
        String id,
        String workspaceId,
        String ownerId,
        String filename,
        String title,
        long expectedSize,
        String expectedHash,
        int chunkSize,
        int chunkCount,
        UploadState state,
        String mediaId,
        Instant expiresAt,
        long finalizeEpoch,
        Instant leaseUntil,
        Instant nextFinalizeAt,
        boolean quotaReserved,
        String errorCode,
        String traceId,
        Instant createdAt) {
}
