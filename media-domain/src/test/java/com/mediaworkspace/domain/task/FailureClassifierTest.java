package com.mediaworkspace.domain.task;

import com.mediaworkspace.contracts.model.TaskErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PROC-02: bad input is never retried forever; environment problems are. */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    @DisplayName("bad or missing input is permanent and must not be retried")
    void permanentCodes() {
        assertThat(classifier.classify(TaskErrorCode.INVALID_MEDIA)).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.classify(TaskErrorCode.UNSUPPORTED_MEDIA)).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.classify(TaskErrorCode.SOURCE_MISSING)).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.classify(TaskErrorCode.HASH_MISMATCH)).isEqualTo(FailureClass.PERMANENT);
        assertThat(classifier.isRetryable(TaskErrorCode.INVALID_MEDIA)).isFalse();
    }

    @Test
    @DisplayName("environment and execution problems are transient")
    void transientCodes() {
        for (TaskErrorCode code : new TaskErrorCode[] {
                TaskErrorCode.PROCESS_START_FAILED, TaskErrorCode.PROCESS_TIMEOUT, TaskErrorCode.DISK_FULL,
                TaskErrorCode.WORKER_LOST, TaskErrorCode.OUTPUT_INVALID, TaskErrorCode.INTERNAL_ERROR}) {
            assertThat(classifier.classify(code)).as("%s", code).isEqualTo(FailureClass.TRANSIENT);
            assertThat(classifier.isRetryable(code)).as("%s", code).isTrue();
        }
    }

    @Test
    @DisplayName("every code has a defined classification, so no failure has an undefined path")
    void everyCodeIsClassified() {
        for (TaskErrorCode code : TaskErrorCode.values()) {
            assertThat(classifier.classify(code)).as("%s", code).isNotNull();
        }
    }
}
