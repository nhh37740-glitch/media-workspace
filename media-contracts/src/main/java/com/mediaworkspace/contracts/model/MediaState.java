package com.mediaworkspace.contracts.model;

/** Publication state of a media asset. Deletion is expressed by {@code media.deleted_at}, not here. */
public enum MediaState {
    /** The original file exists and a task is being worked on. */
    PROCESSING,
    /** A validated playable output and poster exist and may be served. */
    READY,
    /** The task ended in a non-recoverable failure; no playable output is published. */
    FAILED,
    /** Processing was cancelled; previously published output is not served. */
    CANCELLED;

    public boolean isPlayable() {
        return this == READY;
    }
}
