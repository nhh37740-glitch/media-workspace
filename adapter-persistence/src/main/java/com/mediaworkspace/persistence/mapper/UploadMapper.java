package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL for {@code upload_session} and {@code upload_chunk}. */
public interface UploadMapper {

    int insertSession(@Param("s") UploadSession session);

    UploadSession findById(@Param("uploadId") String uploadId);

    UploadSession lockById(@Param("uploadId") String uploadId);

    int countOpenSessions(@Param("workspaceId") String workspaceId, @Param("ownerId") String ownerId);

    /** Inserts unless the index exists; the caller then compares the stored hash. */
    int insertChunk(@Param("uploadId") String uploadId, @Param("c") UploadChunkRecord chunk);

    UploadChunkRecord findChunk(@Param("uploadId") String uploadId, @Param("chunkIndex") int chunkIndex);

    List<UploadChunkRecord> listChunks(@Param("uploadId") String uploadId);

    int countChunks(@Param("uploadId") String uploadId);

    int markFinalizing(@Param("uploadId") String uploadId);

    /**
     * Takes the merge lease and advances the epoch.
     *
     * <p>The epoch increments only when the lease is actually taken, so a finalizer that was
     * superseded cannot publish with a stale epoch.
     */
    int acquireFinalizeLease(@Param("uploadId") String uploadId, @Param("leaseSeconds") long leaseSeconds);

    Long currentFinalizeEpoch(@Param("uploadId") String uploadId);

    int renewFinalizeLease(@Param("uploadId") String uploadId, @Param("epoch") long epoch,
                           @Param("leaseSeconds") long leaseSeconds);

    int releaseFinalizeLease(@Param("uploadId") String uploadId, @Param("epoch") long epoch,
                             @Param("nextAttemptMillis") long nextAttemptMillis);

    int markCompleted(@Param("uploadId") String uploadId, @Param("mediaId") String mediaId,
                      @Param("epoch") long epoch);

    int markFailed(@Param("uploadId") String uploadId, @Param("errorCode") String errorCode,
                   @Param("releaseReservation") boolean releaseReservation);

    int markTerminated(@Param("uploadId") String uploadId, @Param("target") String target,
                       @Param("releaseReservation") boolean releaseReservation);

    List<UploadSession> findExpiredOpen(@Param("limit") int limit);

    List<UploadSession> findFinalizeCandidates(@Param("limit") int limit);

    List<UploadSession> findStaleFinalizing(@Param("limit") int limit);

    List<UploadSession> findTerminalBefore(@Param("lookbackSeconds") long lookbackSeconds,
                                           @Param("limit") int limit);
}
