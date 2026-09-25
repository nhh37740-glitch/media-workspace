package com.mediaworkspace.contracts.model;

/**
 * Lifecycle state of a processing task, as persisted and as published on the wire.
 *
 * <p>This enum is the single definition shared by the database CHECK constraint, the HTTP
 * representation and the Kafka payload. Legal transitions are owned by {@code media-domain}.
 */
public enum TaskState {
    /** Task row exists after a successful upload finalize; the request event may not be consumed yet. */
    WAITING_EVENT,
    /** The request event was consumed and the task may now be claimed by a worker. */
    QUEUED,
    /** A worker holds a valid lease and is executing this generation. */
    RUNNING,
    /** A retryable failure occurred; the task waits for its backoff deadline. */
    RETRY_WAIT,
    /** Terminal success. */
    SUCCEEDED,
    /** Terminal failure: a permanent error, or the attempt budget is exhausted. */
    FAILED,
    /** Terminal cancellation by user request or because the media row was deleted. */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return !isTerminal();
    }
}
