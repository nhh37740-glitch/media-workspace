package com.mediaworkspace.contracts.event;

/** Event types published on the media task topics. */
public enum EventType {
    /** Emitted in the same transaction that creates a task; consumed by the worker. */
    TASK_REQUESTED,
    /** Emitted by the worker that published a valid artifact. */
    TASK_SUCCEEDED,
    /** Emitted when a task reaches its terminal FAILED state. */
    TASK_FAILED,
    /** Emitted when a task is cancelled by the user or by media deletion. */
    TASK_CANCELLED
}
