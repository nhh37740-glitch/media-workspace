package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.ExecutionIdentity;
import com.mediaworkspace.application.port.repository.TaskRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Rate-limits progress writes to at most one per task per second.
 *
 * <p>FFmpeg reports progress far more often than that, and every write is a database round trip
 * that competes with the execution itself. Values are clamped to 0..99: reaching 100 is reserved
 * for the publish transaction, so a progress frame can never make a task look finished.
 */
public class ProgressReporter {

    /** Minimum interval between two persisted progress values for one execution. */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final TaskRepository tasks;
    private final Clock clock;
    private final ExecutionIdentity identity;
    private Instant lastWrite = Instant.EPOCH;
    private int lastValue = -1;

    public ProgressReporter(TaskRepository tasks, Clock clock, ExecutionIdentity identity) {
        this.tasks = tasks;
        this.clock = clock;
        this.identity = identity;
    }

    /**
     * Records progress if enough time passed since the previous write.
     *
     * @param percent raw percentage reported by the encoder
     * @return whether a write was issued
     */
    public boolean report(int percent) {
        int clamped = Math.max(0, Math.min(99, percent));
        Instant now = clock.instant();
        if (clamped == lastValue || Duration.between(lastWrite, now).compareTo(MIN_INTERVAL) < 0) {
            return false;
        }
        lastWrite = now;
        lastValue = clamped;
        return tasks.updateProgress(identity, clamped);
    }

    /** Whether a progress write was ever attempted; used to decide whether to reset on retry. */
    public int lastReported() {
        return lastValue;
    }
}
