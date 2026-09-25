package com.mediaworkspace.domain.task;

import com.mediaworkspace.contracts.model.TaskErrorCode;

import java.util.EnumMap;
import java.util.Map;

/**
 * Decides whether a failure code may be retried.
 *
 * <p>Permanent codes end the task immediately. Transient codes consume one attempt and are retried
 * at most {@link TaskStateMachine#MAX_ATTEMPTS_PER_GENERATION} times per generation.
 */
public final class FailureClassifier {

    private static final Map<TaskErrorCode, FailureClass> CLASSES = new EnumMap<>(TaskErrorCode.class);

    static {
        // The container is unusable, the source is gone, or the uploader lied about the hash.
        // Retrying re-reads the same bytes and reaches the same conclusion.
        CLASSES.put(TaskErrorCode.INVALID_MEDIA, FailureClass.PERMANENT);
        CLASSES.put(TaskErrorCode.UNSUPPORTED_MEDIA, FailureClass.PERMANENT);
        CLASSES.put(TaskErrorCode.SOURCE_MISSING, FailureClass.PERMANENT);
        CLASSES.put(TaskErrorCode.HASH_MISMATCH, FailureClass.PERMANENT);

        // The execution, not the input, went wrong. A later attempt may succeed.
        CLASSES.put(TaskErrorCode.PROCESS_START_FAILED, FailureClass.TRANSIENT);
        CLASSES.put(TaskErrorCode.PROCESS_TIMEOUT, FailureClass.TRANSIENT);
        CLASSES.put(TaskErrorCode.DISK_FULL, FailureClass.TRANSIENT);
        CLASSES.put(TaskErrorCode.WORKER_LOST, FailureClass.TRANSIENT);
        CLASSES.put(TaskErrorCode.OUTPUT_INVALID, FailureClass.TRANSIENT);
        CLASSES.put(TaskErrorCode.INTERNAL_ERROR, FailureClass.TRANSIENT);
    }

    /**
     * Classifies a code.
     *
     * <p>{@code STALE_EXECUTION} is not a failure of the task at all: a newer execution already
     * owns the row. It is reported as TRANSIENT so the state machine always has a defined answer,
     * but the publish path must ignore that outcome instead of writing it. Unlisted codes fall
     * back to TRANSIENT so an unknown failure consumes an attempt rather than ending the task
     * immediately; the attempt budget still bounds it.
     */
    public FailureClass classify(TaskErrorCode code) {
        return CLASSES.getOrDefault(code, FailureClass.TRANSIENT);
    }

    public boolean isRetryable(TaskErrorCode code) {
        return classify(code) == FailureClass.TRANSIENT;
    }
}
