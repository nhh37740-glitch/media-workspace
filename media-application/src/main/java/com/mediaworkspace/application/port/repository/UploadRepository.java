package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.contracts.model.UploadState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Upload sessions, their chunks, and the merge lease a finalizer holds.
 *
 * <p>Every deadline here is evaluated by the database clock, so an API instance with a skewed clock
 * cannot expire somebody else's session or steal a live merge lease.
 */
public interface UploadRepository {

    void insert(UploadSession session);

    Optional<UploadSession> findById(String uploadId);

    /**
     * Locks the session row for a state decision.
     *
     * <p>Every state change takes this lock first, so a complete request, an abort and the expiry
     * sweeper cannot interleave.
     */
    Optional<UploadSession> lockById(String uploadId);

    /** Number of sessions in a non-terminal state owned by a user in a workspace. */
    int countOpenSessions(String workspaceId, String ownerId);

    /**
     * Inserts a chunk row unless the index already exists.
     *
     * @return {@code true} when this call inserted the row, {@code false} when a row already existed
     *         and the caller must compare hashes
     */
    boolean insertChunkIfAbsent(String uploadId, UploadChunkRecord chunk);

    Optional<UploadChunkRecord> findChunk(String uploadId, int index);

    List<UploadChunkRecord> listChunks(String uploadId);

    int countChunks(String uploadId);

    /** Moves an OPEN session to FINALIZING and clears any previous error code. */
    boolean markFinalizing(String uploadId);

    /**
     * Grants the merge lease and increments the finalize epoch.
     *
     * <p>The increment is what makes a superseded finalizer harmless: it may still be writing its
     * own file, but its epoch no longer matches and its publish is rejected.
     *
     * @return the new epoch, or empty when another finalizer still holds a live lease
     */
    Optional<Long> acquireFinalizeLease(String uploadId, Duration leaseDuration);

    /** Extends the merge lease while the caller's epoch is still current. */
    boolean renewFinalizeLease(String uploadId, long epoch, Duration leaseDuration);

    /**
     * Releases the merge lease without changing the session state, so it can be tried again later.
     *
     * <p>Used when the merge result is worth keeping but the task cannot be admitted yet, for
     * example when the global task counter is full.
     */
    boolean releaseFinalizeLease(String uploadId, long epoch, Duration nextAttemptDelay);

    /** Publishes the merge result: COMPLETED with the media id and the reservation moved to used. */
    boolean markCompleted(String uploadId, String mediaId, long epoch);

    /** Marks a failed merge and releases the reservation exactly once. */
    boolean markFailed(String uploadId, String errorCode, boolean releaseReservation);

    /** Terminal state that also settles the reservation exactly once. */
    boolean markTerminated(String uploadId, UploadState target, boolean releaseReservation);

    /** Sessions whose OPEN deadline has passed. */
    List<UploadSession> findExpiredOpen(int limit);

    /** Sessions waiting to be merged, ordered by when they became eligible. */
    List<UploadSession> findFinalizeCandidates(int limit);

    /** Sessions stuck in FINALIZING whose lease lapsed, for recovery by another finalizer. */
    List<UploadSession> findStaleFinalizing(int limit);

    /** Sessions in a terminal state whose files a garbage collector may consider. */
    List<UploadSession> findTerminalBefore(Duration lookback, int limit);
}
