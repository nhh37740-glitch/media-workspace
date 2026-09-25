package com.mediaworkspace.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaworkspace.application.service.UploadChunkCommitService;
import com.mediaworkspace.application.service.UploadService;
import com.mediaworkspace.application.support.CanonicalJson;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.dto.ChunkUploadResponse;
import com.mediaworkspace.contracts.dto.CreateUploadRequest;
import com.mediaworkspace.contracts.dto.UploadCreatedResponse;
import com.mediaworkspace.contracts.dto.UploadStatusResponse;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.contracts.model.UploadState;
import com.mediaworkspace.contracts.trace.TraceFields;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/**
 * Upload session endpoints.
 *
 * <p>The client sends each chunk as a raw body and never chooses the chunk size or the count: the
 * server returns both when the session is created, so the geometry of a transfer is fixed before
 * any bytes move and cannot differ between two clients uploading the same file.
 *
 * <p>Authorization is not decided here. Each use case re-checks the caller's role inside its own
 * transaction, because a check performed only in the web layer would still be honoured after the
 * membership was revoked.
 */
@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private final UploadService uploads;
    private final CurrentUser currentUser;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;

    public UploadController(UploadService uploads, CurrentUser currentUser, RequestContext requestContext,
                            ObjectMapper objectMapper) {
        this.uploads = uploads;
        this.currentUser = currentUser;
        this.requestContext = requestContext;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates an upload session.
     *
     * <p>Requires an idempotency key. A replay with the same body returns the original session and
     * reserves no additional quota; a replay with a different body is a conflict, because treating
     * it as a replay would silently hand back the wrong session.
     */
    @PostMapping("/spaces/{spaceId}/uploads")
    public ResponseEntity<UploadCreatedResponse> createUpload(
            @PathVariable String spaceId,
            @Valid @RequestBody CreateUploadRequest request,
            @RequestHeader(name = TraceFields.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        String actorId = currentUser.requireId();
        requireUsableIdempotencyKey(idempotencyKey);

        // The fingerprint is taken over the normalized DTO, not the raw bytes, so field order and
        // numeric spelling do not change it: the contract defines the hash over canonical values.
        String requestHash = IdempotencyKeys.sha256Hex(
                CanonicalJson.canonicalize(objectMapper.valueToTree(request)));
        String traceId = UUID.randomUUID().toString();
        requestContext.bindTrace(traceId);

        UploadService.CreateResult result =
                uploads.createUpload(actorId, spaceId, request, idempotencyKey, requestHash, traceId);
        return result.replayed()
                ? ResponseEntity.ok(result.response())
                : ResponseEntity.status(201).body(result.response());
    }

    @GetMapping("/uploads/{uploadId}")
    public UploadStatusResponse status(@PathVariable String uploadId) {
        return uploads.status(currentUser.requireId(), uploadId);
    }

    /**
     * Stores one chunk.
     *
     * <p>The body is streamed straight into the staging file: it is never held in memory, so a chunk
     * size larger than the heap is still safe, and the declared content length is passed through as
     * an early check rather than trusted as the size.
     *
     * <p>201 means this call stored the chunk; 200 means the same bytes were already stored, whether
     * by an earlier request or by a concurrent one. Different bytes for an occupied index are a
     * conflict and leave the stored chunk untouched.
     */
    @PutMapping("/uploads/{uploadId}/chunks/{index}")
    public ResponseEntity<ChunkUploadResponse> putChunk(
            @PathVariable String uploadId,
            @PathVariable int index,
            @RequestHeader(name = TraceFields.CHUNK_SHA256_HEADER, required = false) String declaredSha256,
            HttpServletRequest httpRequest) throws IOException {
        String actorId = currentUser.requireId();
        long declaredLength = httpRequest.getContentLengthLong();
        try (InputStream body = httpRequest.getInputStream()) {
            UploadChunkCommitService.ChunkCommit commit =
                    uploads.putChunk(actorId, uploadId, index, declaredLength, declaredSha256, body);
            ChunkUploadResponse response = new ChunkUploadResponse(
                    commit.chunk().index(), commit.chunk().hash(), commit.chunk().sizeBytes());
            return commit.created()
                    ? ResponseEntity.status(201).body(response)
                    : ResponseEntity.ok(response);
        }
    }

    /**
     * Asks for the merge to start.
     *
     * <p>202 while the merge is pending or running, 200 once the media exists. Repeating the request
     * never starts a second merge: the session's own state decides.
     */
    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<UploadStatusResponse> complete(
            @PathVariable String uploadId,
            @RequestBody(required = false) JsonNode body) {
        String actorId = currentUser.requireId();
        UploadStatusResponse status = uploads.completeUpload(actorId, uploadId);
        boolean published = UploadState.COMPLETED.name().equals(status.status());
        return published ? ResponseEntity.ok(status) : ResponseEntity.accepted().body(status);
    }

    /** Terminates an open or failed session and releases its quota reservation. */
    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> abort(@PathVariable String uploadId) {
        uploads.abortUpload(currentUser.requireId(), uploadId);
        return ResponseEntity.noContent().build();
    }

    /** A key has to be long enough to be unguessable and short enough to be a key. */
    private void requireUsableIdempotencyKey(String key) {
        if (key.length() < 8 || key.length() > 128) {
            throw ApplicationException.validation("Idempotency-Key must be 8..128 characters",
                    Map.of("length", key.length()));
        }
    }
}
