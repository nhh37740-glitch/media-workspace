package com.mediaworkspace.application.service;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.IdempotencyRecord;
import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.port.repository.IdempotencyRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.application.support.JsonCodec;
import com.mediaworkspace.contracts.dto.CreateUploadRequest;
import com.mediaworkspace.contracts.dto.ReceivedChunkView;
import com.mediaworkspace.contracts.dto.UploadCreatedResponse;
import com.mediaworkspace.contracts.dto.UploadStatusResponse;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.contracts.model.UploadState;
import com.mediaworkspace.domain.access.RolePolicy;
import com.mediaworkspace.domain.access.SpaceAction;
import com.mediaworkspace.domain.upload.ChunkGeometry;
import com.mediaworkspace.domain.upload.UploadStateMachine;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload lifecycle: create a session under a quota reservation, accept chunks, finish.
 *
 * <p>Each public method is one use case. Business rules live in {@code media-domain}; this class
 * sequences them and owns the transaction. The one place that needs both a transaction and
 * preceding file I/O delegates to {@link UploadChunkCommitService}, because a database transaction
 * must not span the streaming of a chunk body.
 */
public class UploadService {

    /** Route key used to scope upload-creation idempotency records. */
    public static final String CREATE_ROUTE = "POST /spaces/{spaceId}/uploads";

    private final WorkspaceRepository workspaces;
    private final UploadRepository uploads;
    private final IdempotencyRepository idempotency;
    private final TaskRepository tasks;
    private final MediaStorage storage;
    private final UploadChunkCommitService chunkCommit;
    private final UploadAccessGuard guard;
    private final MediaWorkspaceProperties properties;
    private final RolePolicy rolePolicy = new RolePolicy();
    private final UploadStateMachine uploadStates = new UploadStateMachine();
    private final Clock clock;

    public UploadService(WorkspaceRepository workspaces, UploadRepository uploads,
                         IdempotencyRepository idempotency, TaskRepository tasks, MediaStorage storage,
                         UploadChunkCommitService chunkCommit, UploadAccessGuard guard,
                         MediaWorkspaceProperties properties, Clock clock) {
        this.workspaces = workspaces;
        this.uploads = uploads;
        this.idempotency = idempotency;
        this.tasks = tasks;
        this.storage = storage;
        this.chunkCommit = chunkCommit;
        this.guard = guard;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of creating a session, including whether it was a replay of an earlier request. */
    public record CreateResult(UploadCreatedResponse response, boolean replayed) {
    }

    /**
     * Creates an upload session.
     *
     * <p>The quota check and the reservation happen under the workspace row lock, so concurrent
     * creations serialize instead of each reading the same free balance. Replaying the same
     * idempotency key with the same body returns the original response and reserves nothing;
     * replaying it with a different body is a conflict.
     *
     * @param requestHash canonical hash of the request body, used to detect a conflicting replay
     */
    @Transactional
    public CreateResult createUpload(String actorId, String spaceId, CreateUploadRequest request,
                                     String idempotencyKey, String requestHash, String traceId) {
        String keyHash = IdempotencyKeys.sha256Hex(idempotencyKey);
        Optional<IdempotencyRecord> existing =
                idempotency.find(actorId, CREATE_ROUTE, IdempotencyRepository.NO_RESOURCE, keyHash);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        if (request.sizeBytes() > properties.maxUploadSizeBytes()) {
            throw ApplicationException.validation("file exceeds the maximum upload size",
                    Map.of("maxSizeBytes", properties.maxUploadSizeBytes()));
        }

        var workspace = workspaces.lockForReservation(spaceId)
                .orElseThrow(() -> ApplicationException.notFound("space not found", spaceId));
        Role role = workspaces.roleOf(spaceId, actorId).orElse(null);
        if (!rolePolicy.allows(role, SpaceAction.UPLOAD)) {
            throw ApplicationException.notFound("space not found", spaceId);
        }

        long committed = workspace.usedSourceBytes() + workspace.reservedSourceBytes();
        if (committed + request.sizeBytes() > workspace.quotaBytes()) {
            throw new ApplicationException(ApiErrorCode.QUOTA_EXCEEDED,
                    "the workspace source quota would be exceeded", spaceId);
        }
        if (uploads.countOpenSessions(spaceId, actorId) >= properties.maxOpenUploadsPerUser()) {
            throw new ApplicationException(ApiErrorCode.QUOTA_EXCEEDED,
                    "too many unfinished uploads for this user", spaceId);
        }

        ChunkGeometry geometry = new ChunkGeometry(request.sizeBytes(), properties.chunkSizeBytes());
        Instant now = clock.instant();
        String uploadId = UUID.randomUUID().toString();
        UploadSession session = new UploadSession(
                uploadId, spaceId, actorId, request.filename(), request.title(),
                request.sizeBytes(), request.sha256(), properties.chunkSizeBytes(), geometry.chunkCount(),
                UploadState.OPEN, null, now.plus(properties.openUploadTtl()), 0L, null, now,
                true, null, traceId, now);
        uploads.insert(session);
        workspaces.addReservedBytes(spaceId, request.sizeBytes());

        UploadCreatedResponse response = new UploadCreatedResponse(
                uploadId, properties.chunkSizeBytes(), geometry.chunkCount(),
                UploadState.OPEN.name(), session.expiresAt().toString());
        idempotency.insert(new IdempotencyRecord(
                actorId, CREATE_ROUTE, IdempotencyRepository.NO_RESOURCE, keyHash,
                requestHash, JsonCodec.write(response), 201));
        return new CreateResult(response, false);
    }

    private CreateResult replay(IdempotencyRecord record, String requestHash) {
        if (!IdempotencyKeys.constantTimeEquals(record.requestHash(), requestHash)) {
            throw new ApplicationException(ApiErrorCode.IDEMPOTENCY_CONFLICT,
                    "this idempotency key was already used with a different request body");
        }
        return new CreateResult(JsonCodec.read(record.responseJson(), UploadCreatedResponse.class), true);
    }

    /** Reads a session for its uploader or a workspace owner, with the chunks received so far. */
    @Transactional(readOnly = true)
    public UploadStatusResponse status(String actorId, String uploadId) {
        UploadSession session = guard.requireVisible(actorId, uploadId);
        return describe(session);
    }

    private UploadStatusResponse describe(UploadSession session) {
        List<ReceivedChunkView> received = uploads.listChunks(session.id()).stream()
                .sorted(Comparator.comparingInt(UploadChunkRecord::index))
                .map(chunk -> new ReceivedChunkView(chunk.index(), chunk.hash()))
                .toList();
        String taskId = session.mediaId() == null ? null
                : tasks.findByMediaId(session.mediaId()).map(ProcessingTaskRecord::id).orElse(null);
        return new UploadStatusResponse(
                session.id(), session.state().name(), session.chunkSize(), session.chunkCount(),
                received, session.mediaId(), taskId, session.errorCode());
    }

    /**
     * Stores one chunk.
     *
     * <p>The body is streamed to a temporary file while both its length and its SHA-256 are
     * computed. Only a chunk matching its declared hash and its expected length is published as an
     * immutable object, and the row is written afterwards. Re-sending identical bytes is a no-op
     * that returns the stored values; sending different bytes for an occupied index is a conflict
     * that leaves the stored chunk untouched.
     */
    public UploadChunkCommitService.ChunkCommit putChunk(String actorId, String uploadId, int index,
                                                         long declaredLength, String declaredSha256,
                                                         InputStream body) {
        UploadSession session = guard.requireVisible(actorId, uploadId);
        if (session.state() != UploadState.OPEN) {
            throw ApplicationException.conflict("upload is not open for new chunks", uploadId);
        }
        ChunkGeometry geometry = new ChunkGeometry(session.expectedSize(), session.chunkSize());
        if (!geometry.isValidIndex(index)) {
            throw new ApplicationException(ApiErrorCode.BAD_REQUEST, "chunk index is out of range", uploadId);
        }
        long expectedLength = geometry.expectedLengthOf(index);
        if (declaredLength >= 0 && declaredLength != expectedLength) {
            throw new ApplicationException(ApiErrorCode.CHUNK_SIZE_MISMATCH,
                    "the declared content length does not match the expected chunk length", uploadId);
        }
        if (declaredSha256 != null && !declaredSha256.matches("[0-9a-f]{64}")) {
            throw new ApplicationException(ApiErrorCode.BAD_REQUEST,
                    "X-Chunk-SHA256 must be 64 lowercase hex characters", uploadId);
        }

        StagedChunk staged = stageChunk(uploadId, index, expectedLength, body);
        try {
            if (!IdempotencyKeys.constantTimeEquals(staged.sha256(), declaredSha256)) {
                throw new ApplicationException(ApiErrorCode.VALIDATION_FAILED,
                        "the chunk body does not match the declared X-Chunk-SHA256", uploadId);
            }
            UploadChunkRecord candidate = new UploadChunkRecord(
                    index, staged.sha256(), expectedLength, staged.publishedKey());
            return chunkCommit.commit(actorId, uploadId, candidate);
        } finally {
            staged.release();
        }
    }

    private StagedChunk stageChunk(String uploadId, int index, long expectedLength, InputStream body) {
        String nonce = UUID.randomUUID().toString();
        Path tempPath = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            tempPath = storage.createTemporaryFile("tmp/chunk/" + uploadId + "/" + index + "-" + nonce);
            long written;
            try (InputStream in = body;
                 OutputStream fileOut = Files.newOutputStream(tempPath);
                 DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
                written = copyBounded(in, digestOut, expectedLength, uploadId);
                digestOut.flush();
            }
            if (written != expectedLength) {
                throw new ApplicationException(ApiErrorCode.CHUNK_SIZE_MISMATCH,
                        "the chunk body length does not match the expected chunk length", uploadId);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            String publishedKey = "chunk/" + uploadId + "/" + index + "-" + sha256;
            try (InputStream staged = Files.newInputStream(tempPath)) {
                storage.putImmutable(publishedKey, staged);
            }
            Files.deleteIfExists(tempPath);
            return new StagedChunk(publishedKey, sha256, null);
        } catch (MediaStorage.StorageException e) {
            throw new ApplicationException(ApiErrorCode.SERVICE_UNAVAILABLE,
                    "the storage volume rejected the chunk", uploadId, null, e);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ApplicationException(ApiErrorCode.INTERNAL_ERROR,
                    "could not stage the chunk", uploadId, null, e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                    // The delayed collector removes stray temporaries.
                }
            }
        }
    }

    private long copyBounded(InputStream in, OutputStream out, long limit, String uploadId) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new ApplicationException(ApiErrorCode.CHUNK_SIZE_MISMATCH,
                        "the chunk body is longer than the expected chunk length", uploadId);
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    private record StagedChunk(String publishedKey, String sha256, Path temporary) {
        void release() {
            if (temporary == null) {
                return;
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort; the collector handles the rest.
            }
        }
    }

    /**
     * Moves an OPEN session to FINALIZING once every chunk is present.
     *
     * <p>Idempotent by state: a session already finalizing reports FINALIZING and a completed one
     * reports its result, so a retried request never starts a second merge.
     */
    @Transactional
    public UploadStatusResponse completeUpload(String actorId, String uploadId) {
        UploadSession session = guard.requireVisible(actorId, uploadId);
        if (session.state() != UploadState.OPEN) {
            return describe(session);
        }
        int stored = uploads.countChunks(uploadId);
        if (stored != session.chunkCount()) {
            throw new ApplicationException(ApiErrorCode.CHUNKS_MISSING,
                    "not every chunk has been received", uploadId,
                    Map.of("expected", session.chunkCount(), "received", stored), null);
        }
        uploads.markFinalizing(uploadId);
        UploadSession updated = uploads.findById(uploadId).orElse(session);
        return describe(updated);
    }

    /** Terminates an OPEN or FAILED session, releasing its quota reservation exactly once. */
    @Transactional
    public void abortUpload(String actorId, String uploadId) {
        UploadSession session = guard.requireVisible(actorId, uploadId);
        UploadState target = uploadStates
                .next(session.state(), UploadStateMachine.Action.ABORT)
                .orElseThrow(() -> ApplicationException.conflict(
                        "only an open or failed upload can be aborted", uploadId));
        boolean release = session.quotaReserved() && session.state().holdsReservation()
                && !target.holdsReservation();
        uploads.markTerminated(uploadId, target, release);
    }

}
