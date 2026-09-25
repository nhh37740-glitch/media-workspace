package com.mediaworkspace.application.model;

/**
 * The global unfinished-task counter.
 *
 * <p>The counter row is the serialization point for admitting new work: it is locked in the same
 * transaction that creates or terminates a task, so concurrent uploads cannot both observe a free
 * slot and both take it.
 *
 * @param name        counter name; the processing counter is a single fixed row
 * @param activeCount current number of unfinished tasks
 * @param maxCount    configured maximum, stored on the row so the limit is data
 */
public record CapacitySnapshot(String name, int activeCount, int maxCount) {

    public boolean hasFreeSlot() {
        return activeCount < maxCount;
    }
}
