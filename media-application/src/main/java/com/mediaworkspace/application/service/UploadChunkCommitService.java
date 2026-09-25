package com.mediaworkspace.application.service;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.UploadState;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The transactional half of storing a chunk.
 *
 * <p>Deliberately a separate bean from {@link UploadService}: the chunk body has already been
 * streamed to disk before this runs, and a database transaction must not be held open across that
 * I/O. Calling a method on this bean from the streaming code is a genuine bean boundary, so the
 * transaction advice actually applies; a {@code @Transactional} private method on the caller would
 * be silently ignored.
 */
public class UploadChunkCommitService {

    private final UploadRepository uploads;
    private final UploadAccessGuard guard;

    public UploadChunkCommitService(UploadRepository uploads, UploadAccessGuard guard) {
        this.uploads = uploads;
        this.guard = guard;
    }

    /** Outcome of recording a chunk: the stored row and whether this call created it. */
    public record ChunkCommit(UploadChunkRecord chunk, boolean created) {
    }

    /**
     * Records a chunk that is already published on the storage volume.
     *
     * <p>Runs in one short transaction that locks the session, so a concurrent complete request
     * cannot slip in between the state check and the insert.
     *
     * @param candidate the chunk row to insert
     * @throws ApplicationException with {@code CONFLICT} when the session stopped accepting chunks
     *                              meanwhile, or {@code CHUNK_HASH_MISMATCH} when the index is
     *                              already occupied by different bytes
     */
    @Transactional
    public ChunkCommit commit(String actorId, String uploadId, UploadChunkRecord candidate) {
        UploadSession locked = guard.requireVisible(actorId, uploadId);
        if (locked.state() != UploadState.OPEN) {
            throw ApplicationException.conflict("upload is not open for new chunks", uploadId);
        }
        Optional<UploadChunkRecord> existing = uploads.findChunk(uploadId, candidate.index());
        if (existing.isPresent()) {
            UploadChunkRecord stored = existing.get();
            if (!IdempotencyKeys.constantTimeEquals(stored.hash(), candidate.hash())) {
                throw new ApplicationException(ApiErrorCode.CHUNK_HASH_MISMATCH,
                        "this chunk index already holds different bytes", uploadId);
            }
            return new ChunkCommit(stored, false);
        }
        uploads.insertChunkIfAbsent(uploadId, candidate);
        UploadChunkRecord stored = uploads.findChunk(uploadId, candidate.index())
                .orElseThrow(() -> ApplicationException.conflict("chunk could not be recorded", uploadId));
        return new ChunkCommit(stored, true);
    }
}
