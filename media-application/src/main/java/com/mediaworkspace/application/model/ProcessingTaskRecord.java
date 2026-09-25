package com.mediaworkspace.application.model;

import com.mediaworkspace.contracts.model.TaskState;
import com.mediaworkspace.contracts.model.TranscodePreset;

import java.time.Instant;

/**
 * A processing task row.
 *
 * @param generation     increments on retry; a late result from an older generation is ignored
 * @param attempt        executions started in the current generation, 0..3
 * @param executionEpoch increments on every claim; identifies one execution attempt
 * @param workerId       instance id of the worker currently holding the lease, or {@code null}
 * @param leaseUntil     lease deadline; a worker may publish only while this is in the future
 * @param nextRunAt      earliest instant the task may be claimed again
 * @param version        monotonic row version, used as the SSE event id
 */
public record ProcessingTaskRecord(
        String id,
        String mediaId,
        int generation,
        TaskState state,
        TranscodePreset preset,
        int attempt,
        long executionEpoch,
        String workerId,
        Instant leaseUntil,
        Instant nextRunAt,
        int progress,
        long version,
        String errorCode,
        String traceId,
        Instant createdAt,
        Instant updatedAt) {

    /** The full identity of one execution, as required by every conditional update. */
    public ExecutionIdentity identity() {
        return new ExecutionIdentity(id, generation, executionEpoch, workerId);
    }
}
