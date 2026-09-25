package com.mediaworkspace.contracts.dto;

/**
 * Payload of an SSE {@code state} or {@code snapshot} frame.
 *
 * <p>Carries the task's monotonic {@code version} so a client can drop out-of-order frames and
 * detect a missed terminal state. The client then confirms with {@code GET /tasks/{id}}.
 *
 * @param taskId   task identifier
 * @param state    state at the time the frame was produced
 * @param progress 0..100 hint
 * @param version  task row version, also used as the SSE event id
 */
public record TaskEventView(String taskId, String state, int progress, long version) {
}
