package com.mediaworkspace.contracts.trace;

/**
 * Canonical names of the correlation fields carried through MDC, HTTP headers and Kafka events.
 *
 * <p>Every module uses these constants instead of string literals so a rename is a contract change
 * rather than a silent divergence. A unified name set, not a claim that OpenTelemetry is deployed.
 */
public final class TraceFields {

    /** One business chain, created when the upload is created and persisted with it. */
    public static final String TRACE_ID = "traceId";
    /** One HTTP request. A retry gets a new one. */
    public static final String REQUEST_ID = "requestId";
    /** One module invocation or asynchronous span. */
    public static final String INVOCATION_ID = "invocationId";
    /** The invocation that directly called this one; asynchronous links use causationEventId. */
    public static final String PARENT_INVOCATION_ID = "parentInvocationId";

    public static final String EVENT_ID = "eventId";
    public static final String CAUSATION_EVENT_ID = "causationEventId";
    public static final String PRODUCER_INVOCATION_ID = "producerInvocationId";

    public static final String MODULE = "module";
    public static final String OPERATION = "operation";
    public static final String PHASE = "phase";
    public static final String INPUT_REF = "inputRef";
    public static final String OUTPUT_REF = "outputRef";

    public static final String TASK_ID = "taskId";
    public static final String MEDIA_ID = "mediaId";
    public static final String GENERATION = "generation";
    public static final String ATTEMPT = "attempt";
    public static final String EXECUTION_EPOCH = "executionEpoch";
    public static final String WORKER_ID = "workerId";

    public static final String DURATION_MS = "durationMs";
    public static final String OUTCOME = "outcome";
    public static final String ERROR_CODE = "errorCode";

    /** HTTP header a client may send; the API always replaces it with a freshly generated id. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** Response header echoing the server-generated request id. */
    public static final String REQUEST_ID_RESPONSE_HEADER = "X-Request-Id";
    /** Per-chunk content hash supplied by the uploader. */
    public static final String CHUNK_SHA256_HEADER = "X-Chunk-SHA256";
    /** Idempotency key for upload creation and retry. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private TraceFields() {
    }
}
