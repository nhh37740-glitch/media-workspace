package com.mediaworkspace.contracts.dto;

/**
 * Result of {@code GET /tasks/{id}}.
 *
 * <p>This is the authoritative task state. Progress values pushed over SSE are hints only; a
 * client confirms a terminal state with this endpoint.
 *
 * @param taskId        identifier
 * @param mediaId       the media this task produces
 * @param state         current lifecycle state
 * @param generation    generation number; a retry increments it
 * @param attempt       executions started in this generation, 0..3
 * @param progress      0..100; reaches 100 only when the publish transaction commits
 * @param errorCode     failure code for a FAILED task, otherwise {@code null}
 * @param version       monotonically increasing row version, used as the SSE event id
 * @param updatedAt     last state change, UTC ISO-8601
 */
public record TaskView(
        String taskId,
        String mediaId,
        String state,
        int generation,
        int attempt,
        int progress,
        String errorCode,
        long version,
        String updatedAt) {
}
