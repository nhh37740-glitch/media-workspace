package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.CapacitySnapshot;

import java.util.Optional;

/**
 * The global unfinished-task counter.
 *
 * <p>One row, locked in the same transaction that creates or terminates a task, which is what
 * makes admission exact under concurrency: two uploads that both finalize at the same moment
 * serialize on this row instead of both observing a free slot.
 */
public interface CapacityRepository {

    /** Counter name of the global unfinished-task limit. */
    String PROCESSING = "processing";

    /**
     * Locks the counter row and returns its current value.
     *
     * <p>Must be acquired before the task row when a transaction needs both, per the fixed lock
     * order: capacity, workspace, upload, media, task, attempt.
     */
    Optional<CapacitySnapshot> lock(String name);

    /** Increments the counter; the caller holds the row lock. */
    void increment(String name);

    /** Decrements the counter, never below zero; the caller holds the row lock. */
    void decrement(String name);

    /** Reads the counter without locking, for health reporting. */
    Optional<CapacitySnapshot> read(String name);

    /**
     * Recomputes the counter from the task table and reports both values.
     *
     * <p>Used by the consistency check. It reports a disagreement rather than silently repairing
     * it, because a silent repair would hide the bug that caused the drift.
     */
    CapacityDrift checkDrift(String name);

    /**
     * @param counterValue what the counter row says
     * @param actualValue  what counting unfinished tasks says
     */
    record CapacityDrift(String name, int counterValue, int actualValue) {
        public boolean agrees() {
            return counterValue == actualValue;
        }
    }
}
