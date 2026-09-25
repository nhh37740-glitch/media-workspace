package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.contracts.model.UploadState;
import com.mediaworkspace.persistence.mapper.UploadMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis implementation of {@link UploadRepository}.
 *
 * <p>No transaction is started here: the adapter is called from an application-layer use case that
 * owns the boundary, so several statements that must be atomic stay together. The mapper statements
 * themselves decide deadlines using the database clock.
 */
public class UploadRepositoryAdapter implements UploadRepository {

    private final UploadMapper mapper;

    public UploadRepositoryAdapter(UploadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(UploadSession session) {
        mapper.insertSession(session);
    }

    @Override
    public Optional<UploadSession> findById(String uploadId) {
        return Optional.ofNullable(mapper.findById(uploadId));
    }

    @Override
    public Optional<UploadSession> lockById(String uploadId) {
        return Optional.ofNullable(mapper.lockById(uploadId));
    }

    @Override
    public int countOpenSessions(String workspaceId, String ownerId) {
        return mapper.countOpenSessions(workspaceId, ownerId);
    }

    @Override
    public boolean insertChunkIfAbsent(String uploadId, UploadChunkRecord chunk) {
        return mapper.insertChunk(uploadId, chunk) > 0;
    }

    @Override
    public Optional<UploadChunkRecord> findChunk(String uploadId, int index) {
        return Optional.ofNullable(mapper.findChunk(uploadId, index));
    }

    @Override
    public List<UploadChunkRecord> listChunks(String uploadId) {
        return mapper.listChunks(uploadId);
    }

    @Override
    public int countChunks(String uploadId) {
        return mapper.countChunks(uploadId);
    }

    @Override
    public boolean markFinalizing(String uploadId) {
        return mapper.markFinalizing(uploadId) > 0;
    }

    @Override
    public Optional<Long> acquireFinalizeLease(String uploadId, Duration leaseDuration) {
        int claimed = mapper.acquireFinalizeLease(uploadId, leaseDuration.toSeconds());
        if (claimed == 0) {
            return Optional.empty();
        }
        // Read back the epoch the statement just assigned; the row is locked by the caller's
        // transaction, so no other finalizer can have moved it in between.
        return Optional.ofNullable(mapper.currentFinalizeEpoch(uploadId));
    }

    @Override
    public boolean renewFinalizeLease(String uploadId, long epoch, Duration leaseDuration) {
        return mapper.renewFinalizeLease(uploadId, epoch, leaseDuration.toSeconds()) > 0;
    }

    @Override
    public boolean releaseFinalizeLease(String uploadId, long epoch, Duration nextAttemptDelay) {
        return mapper.releaseFinalizeLease(uploadId, epoch, nextAttemptDelay.toMillis()) > 0;
    }

    @Override
    public boolean markCompleted(String uploadId, String mediaId, long epoch) {
        return mapper.markCompleted(uploadId, mediaId, epoch) > 0;
    }

    @Override
    public boolean markFailed(String uploadId, String errorCode, boolean releaseReservation) {
        return mapper.markFailed(uploadId, errorCode, releaseReservation) > 0;
    }

    @Override
    public boolean markTerminated(String uploadId, UploadState target, boolean releaseReservation) {
        return mapper.markTerminated(uploadId, target.name(), releaseReservation) > 0;
    }

    @Override
    public List<UploadSession> findExpiredOpen(int limit) {
        return mapper.findExpiredOpen(limit);
    }

    @Override
    public List<UploadSession> findFinalizeCandidates(int limit) {
        return mapper.findFinalizeCandidates(limit);
    }

    @Override
    public List<UploadSession> findStaleFinalizing(int limit) {
        return mapper.findStaleFinalizing(limit);
    }

    @Override
    public List<UploadSession> findTerminalBefore(Duration lookback, int limit) {
        return mapper.findTerminalBefore(lookback.toSeconds(), limit);
    }
}
