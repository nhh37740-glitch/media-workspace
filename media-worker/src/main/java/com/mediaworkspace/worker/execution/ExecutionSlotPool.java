package com.mediaworkspace.worker.execution;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds how many tasks this worker runs at once.
 *
 * <p>A permit is taken <b>before</b> a task is claimed, never after. Claiming first and then waiting
 * for capacity would leave tasks leased to a worker that cannot run them, so they would sit in
 * RUNNING until their lease lapsed and then be recovered by somebody else - wasted work, and a task
 * that appears stuck for no reason.
 *
 * <p>There is no queue behind this pool. "No capacity" means "do not claim", which is what keeps the
 * backlog in the database where it is visible and bounded, instead of in the heap of this process
 * where it is neither.
 */
public class ExecutionSlotPool {

    private final Semaphore permits;
    private final int capacity;
    private final AtomicInteger inUse = new AtomicInteger();

    public ExecutionSlotPool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a worker needs at least one execution slot");
        }
        this.capacity = capacity;
        this.permits = new Semaphore(capacity, true);
    }

    /**
     * Takes a permit without waiting.
     *
     * @return {@code true} when a slot is now held and must be released by the caller
     */
    public boolean tryAcquire() {
        if (permits.tryAcquire()) {
            inUse.incrementAndGet();
            return true;
        }
        return false;
    }

    /** Returns a permit. Safe to call in a {@code finally}; a double release would not over-fill. */
    public void release() {
        if (inUse.get() > 0) {
            inUse.decrementAndGet();
            permits.release();
        }
    }

    public int capacity() {
        return capacity;
    }

    public int inUse() {
        return inUse.get();
    }

    public boolean hasFreeSlot() {
        return permits.availablePermits() > 0;
    }
}
