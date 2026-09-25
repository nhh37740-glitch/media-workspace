package com.mediaworkspace.domain.task;

/** Every event that can move a task between states. */
public enum TaskAction {
    /** The worker consumed the request event for the current generation. */
    REQUEST_EVENT_CONSUMED,
    /** A worker claimed the task under a lease. */
    CLAIM,
    /** The publish transaction committed a validated artifact. */
    SUCCEED,
    /** An execution failed with a code that may be retried. */
    RETRYABLE_FAILURE,
    /** An execution failed with a code that must not be retried. */
    PERMANENT_FAILURE,
    /** A user cancelled the task, or its media row was deleted. */
    CANCEL,
    /** A user asked to retry a terminal FAILED or CANCELLED task. */
    RETRY_REQUESTED
}
