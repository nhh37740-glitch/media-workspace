package com.mediaworkspace.application.model;

import java.time.Instant;

/**
 * One execution record shown on the task detail page.
 *
 * @param errorSummary sanitized, truncated failure summary; raw stacks never reach this field
 */
public record TaskAttemptRecord(
        String id,
        String taskId,
        int generation,
        int attempt,
        long executionEpoch,
        String workerId,
        String state,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        String errorCode,
        String errorSummary) {
}
