package com.mediaworkspace.contracts.error;

/**
 * Stable error codes returned in the {@code code} field of the error body, with the HTTP status
 * the API must use. Clients branch on the code, not on the message text.
 *
 * <p>The status is a plain {@code int} so this module stays free of web-framework types: the
 * contracts JAR is on the classpath of the pure domain module as well.
 */
public enum ApiErrorCode {

    /** No valid session. */
    AUTH_REQUIRED(401),
    /** Wrong username or password. */
    INVALID_CREDENTIALS(401),
    /** Authenticated member without the role the action requires. */
    FORBIDDEN(403),
    /** Missing or mismatched CSRF token on a state-changing request. */
    CSRF_INVALID(403),
    /** Absent, or not visible to this actor. The response never distinguishes the two. */
    NOT_FOUND(404),
    /** The resource exists but its state forbids this action. */
    STATE_CONFLICT(409),
    /** {@code complete} was called while chunks are still missing. */
    CHUNKS_MISSING(409),
    /** The same chunk index was uploaded with different bytes. */
    CHUNK_HASH_MISMATCH(409),
    /** Optimistic lock version did not match. */
    VERSION_CONFLICT(409),
    /** Same idempotency key reused with a different request body. */
    IDEMPOTENCY_CONFLICT(409),
    /** Chunk body length did not match the declared chunk size. */
    CHUNK_SIZE_MISMATCH(400),
    /** Malformed request: bad JSON, bad UUID, bad header. */
    BAD_REQUEST(400),
    /** Well-formed but semantically invalid input. */
    VALIDATION_FAILED(422),
    /** Request body exceeded a configured limit. */
    PAYLOAD_TOO_LARGE(413),
    /** Requested byte range cannot be satisfied. */
    RANGE_NOT_SATISFIABLE(416),
    /** Source-file quota or per-user open-session count would be exceeded. */
    QUOTA_EXCEEDED(429),
    /** The global unfinished-task counter is full. */
    CAPACITY_EXCEEDED(429),
    /** A required dependency (database, Kafka) is temporarily unavailable. */
    SERVICE_UNAVAILABLE(503),
    /** Unexpected server-side failure. Details are not exposed. */
    INTERNAL_ERROR(500);

    private final int status;

    ApiErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
