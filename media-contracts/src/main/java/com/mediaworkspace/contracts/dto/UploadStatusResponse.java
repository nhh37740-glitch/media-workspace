package com.mediaworkspace.contracts.dto;

import java.util.List;

/**
 * Result of {@code GET /uploads/{id}} and of {@code POST /uploads/{id}/complete}.
 *
 * @param uploadId       session identifier
 * @param status         current session state
 * @param chunkSize      byte size of a full chunk
 * @param chunkCount     total chunk count
 * @param receivedChunks chunks already stored, so a client resumes instead of restarting
 * @param mediaId        set once the original file is published, otherwise {@code null}
 * @param taskId         set together with {@code mediaId}, otherwise {@code null}
 * @param errorCode      failure code when {@code status} is FAILED, otherwise {@code null}
 */
public record UploadStatusResponse(
        String uploadId,
        String status,
        int chunkSize,
        int chunkCount,
        List<ReceivedChunkView> receivedChunks,
        String mediaId,
        String taskId,
        String errorCode) {
}
