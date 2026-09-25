package com.mediaworkspace.contracts.dto;

/**
 * One execution of a task generation, as shown on the task detail page.
 *
 * <p>{@code errorSummary} is a sanitized, length-limited excerpt. Raw stack traces, server paths
 * and SQL stay on the server and are never returned here.
 *
 * @param attemptId    identifier of this execution
 * @param attempt      execution number within the generation, 1-based
 * @param generation   generation this execution belongs to
 * @param state        outcome of the execution
 * @param startedAt    start instant, UTC ISO-8601
 * @param finishedAt   end instant or {@code null} while running
 * @param errorCode    failure code or {@code null}
 * @param errorSummary sanitized failure summary or {@code null}
 */
public record TaskAttemptView(
        String attemptId,
        int attempt,
        int generation,
        String state,
        String startedAt,
        String finishedAt,
        String errorCode,
        String errorSummary) {
}
