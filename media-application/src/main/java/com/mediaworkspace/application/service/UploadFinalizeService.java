package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Merges a finalized upload into the immutable original file and publishes it.
 *
 * <p>Three phases, deliberately separated:
 * <ol>
 *   <li>a short transaction acquires the merge lease and advances the finalize epoch;</li>
 *   <li>the merge streams bytes outside any transaction, because holding a database transaction
 *       across the concatenation of a gigabyte would block other writers;</li>
 *   <li>a second short transaction re-checks that this finalizer's epoch is still current, then
 *       publishes the media row, the task, the request event and the quota movement atomically.</li>
 * </ol>
 *
 * <p>The merged file lives at a key derived from the uploader's declared whole-file hash and is
 * written there only after that hash was verified. The key is therefore a content address: an
 * earlier merge of exactly these chunks can be reused instead of re-read, and a superseded
 * finalizer can never publish bytes the uploader did not declare.
 */
public class UploadFinalizeService {

    private static final Logger log = LoggerFactory.getLogger(UploadFinalizeService.class);

    private final UploadRepository uploads;
    private final UploadFinalizeTransactionService transactions;
    private final MediaStorage storage;
    private final Clock clock;

    public UploadFinalizeService(UploadRepository uploads, UploadFinalizeTransactionService transactions,
                                 MediaStorage storage, Clock clock) {
        this.uploads = uploads;
        this.transactions = transactions;
        this.storage = storage;
        this.clock = clock;
    }

    /** What one finalize pass did, for the scheduler's log line and metrics. */
    public enum Outcome {
        /** No session was ready to be merged. */
        NONE,
        /** A media row and a task were published. */
        PUBLISHED,
        /** The merged file did not match, or could not be produced; the session is FAILED. */
        FAILED,
        /** The global task counter was full; the lease was released for a later attempt. */
        CAPACITY_WAIT,
        /** Another finalizer owns the session, or its epoch moved on. Nothing was written. */
        SUPERSEDED
    }

    /** Merges one of the sessions that is ready, if any. */
    public Outcome finalizeNext(String requestId) {
        Optional<String> candidate = transactions.pickCandidate();
        return candidate.map(uploadId -> finalizeSession(uploadId, requestId)).orElse(Outcome.NONE);
    }

    /** Runs one finalize pass for a specific session. */
    public Outcome finalizeSession(String uploadId, String requestId) {
        Optional<UploadFinalizeTransactionService.FinalizeLease> lease =
                transactions.acquireLease(uploadId);
        if (lease.isEmpty()) {
            return Outcome.SUPERSEDED;
        }
        var held = lease.get();
        MergeResult merged = merge(held.session());
        return switch (merged.status()) {
            case OK -> transactions.publish(held, merged.storageKey(), merged.sizeBytes(), requestId);
            case HASH_MISMATCH -> transactions.failMerge(held, TaskErrorCode.HASH_MISMATCH,
                    "the merged file does not match the declared SHA-256");
            case DISK_FULL -> transactions.failMerge(held, TaskErrorCode.DISK_FULL,
                    "the storage volume ran out of space while merging");
            case STORAGE_ERROR -> transactions.failMerge(held, TaskErrorCode.INTERNAL_ERROR,
                    "the merged file could not be produced");
        };
    }

    private enum MergeStatus {
        OK,
        HASH_MISMATCH,
        DISK_FULL,
        STORAGE_ERROR
    }

    private record MergeResult(MergeStatus status, String storageKey, long sizeBytes) {
        static MergeResult of(MergeStatus status) {
            return new MergeResult(status, null, 0);
        }
    }

    private MergeResult merge(UploadSession session) {
        String mergeKey = mergeKeyOf(session);
        try {
            if (storage.exists(mergeKey) && storage.sizeOf(mergeKey) == session.expectedSize()) {
                return new MergeResult(MergeStatus.OK, mergeKey, session.expectedSize());
            }
            return mergeNow(session, mergeKey);
        } catch (MediaStorage.StorageException e) {
            log.warn("merge failed for upload {}: {}", session.id(), e.getMessage());
            return MergeResult.of(isDiskFull(e) ? MergeStatus.DISK_FULL : MergeStatus.STORAGE_ERROR);
        }
    }

    private MergeResult mergeNow(UploadSession session, String mergeKey) throws MediaStorage.StorageException {
        List<UploadChunkRecord> chunks = uploads.listChunks(session.id()).stream()
                .sorted(Comparator.comparingInt(UploadChunkRecord::index))
                .toList();
        Path tempPath = storage.createTemporaryFile("tmp/merge/" + session.id() + "/" + UUID.randomUUID());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written;
            try (OutputStream fileOut = Files.newOutputStream(tempPath);
                 DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
                written = 0;
                for (UploadChunkRecord chunk : chunks) {
                    try (InputStream in = storage.open(chunk.storageKey())) {
                        written += in.transferTo(digestOut);
                    }
                }
                digestOut.flush();
            }
            if (written != session.expectedSize()) {
                return MergeResult.of(MergeStatus.STORAGE_ERROR);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (!IdempotencyKeys.constantTimeEquals(sha256, session.expectedHash())) {
                return MergeResult.of(MergeStatus.HASH_MISMATCH);
            }
            try (InputStream staged = Files.newInputStream(tempPath)) {
                storage.putImmutable(mergeKey, staged);
            }
            return new MergeResult(MergeStatus.OK, mergeKey, written);
        } catch (NoSuchAlgorithmException e) {
            return MergeResult.of(MergeStatus.STORAGE_ERROR);
        } catch (IOException e) {
            return MergeResult.of(isDiskFull(e) ? MergeStatus.DISK_FULL : MergeStatus.STORAGE_ERROR);
        } finally {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
                // The delayed collector removes stray temporaries.
            }
        }
    }

    private boolean isDiskFull(Throwable e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor.getMessage() != null && cursor.getMessage().contains("No space left on device")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /** Content-addressed merge key: the declared hash is part of the path. */
    static String mergeKeyOf(UploadSession session) {
        return "merge/" + session.id() + "/" + session.expectedHash() + "/original.bin";
    }

    /**
     * Releases sessions whose merge lease lapsed so another finalizer can take over.
     *
     * <p>A lapsed lease means the previous finalizer is gone or wedged. The session stays
     * FINALIZING and keeps its reservation; only the lease is cleared, because an unknown outcome
     * must not release a reservation the session may still need.
     */
    public int recoverStaleLeases(int limit) {
        int recovered = 0;
        for (UploadSession session : transactions.staleFinalizing(limit)) {
            // Zero delay: the session is immediately eligible for another finalizer, because the
            // previous lease already lapsed.
            if (transactions.releaseLease(session.id(), session.finalizeEpoch(), Duration.ZERO)) {
                recovered++;
            }
        }
        return recovered;
    }

    /** Expires OPEN sessions whose deadline passed, releasing each reservation exactly once. */
    public int expireOpenSessions(int limit) {
        return transactions.expireOpenSessions(limit);
    }
}
