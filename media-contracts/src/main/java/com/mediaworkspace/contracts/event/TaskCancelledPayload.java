package com.mediaworkspace.contracts.event;

/**
 * Body of {@code TASK_CANCELLED}.
 *
 * @param reason why the task was cancelled
 */
public record TaskCancelledPayload(CancelReason reason) implements EventPayload {

    /** Cancellation causes recorded with the task history. */
    public enum CancelReason {
        /** A user with EDITOR or OWNER role cancelled the task. */
        USER_REQUEST,
        /** The media row was deleted, which cancels any unfinished task. */
        MEDIA_DELETED
    }
}
