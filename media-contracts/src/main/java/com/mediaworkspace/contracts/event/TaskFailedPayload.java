package com.mediaworkspace.contracts.event;

/**
 * Body of {@code TASK_FAILED}.
 *
 * @param attemptId identifier of the attempt that failed
 * @param errorCode stable failure code
 * @param retryable always {@code false}: this event is only emitted for a terminal failure, so a
 *                  consumer must never reschedule the task from it
 */
public record TaskFailedPayload(String attemptId, String errorCode, boolean retryable) implements EventPayload {
}
