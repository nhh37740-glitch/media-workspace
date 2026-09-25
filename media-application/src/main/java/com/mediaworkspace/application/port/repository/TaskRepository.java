package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.ExecutionIdentity;
import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.model.PublishedArtifacts;
import com.mediaworkspace.application.model.TaskAttemptRecord;
import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.contracts.model.TaskErrorCode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Task rows, their execution leases, and their attempt history.
 *
 * <p>Two rules hold everywhere in this port:
 * <ul>
 *   <li>Lease and deadline comparisons use the database clock ({@code UTC_TIMESTAMP(6)}), never a
 *       value the caller computed locally. Two workers with slightly different clocks must still
 *       agree on who holds a lease.</li>
 *   <li>Every mutating method takes the {@link ExecutionIdentity} of the execution that believes it
 *       owns the row and reports how many rows it changed. Zero affected rows means the execution
 *       was superseded — cancelled, retried, re-leased, or its media deleted — and its result must
 *       be discarded rather than written again.</li>
 * </ul>
 */
public interface TaskRepository {

    void insert(String taskId, String mediaId, String traceId, String preset, String requestId);

    Optional<ProcessingTaskRecord> findById(String taskId);

    /** The task of a media; {@code media_id} is unique, so this is at most one row. */
    Optional<ProcessingTaskRecord> findByMediaId(String mediaId);

    Optional<ProcessingTaskRecord> lockById(String taskId);

    /**
     * Claims the next due task for {@code workerId}.
     *
     * <p>One short transaction: skips rows another worker already locked, then advances the attempt
     * counter and the execution epoch and opens an attempt row. The caller must already hold a local
     * execution permit, so a task is never taken without somewhere to run it.
     */
    Optional<TaskLease> claim(String workerId, Duration leaseDuration);

    /**
     * Extends the lease of a running execution.
     *
     * @return {@code false} when the row no longer matches the identity or the lease already lapsed
     *         by database time; the caller must then terminate its child process and discard the
     *         result
     */
    boolean renew(TaskLease lease, Duration leaseDuration);

    /**
     * Publishes a validated artifact set.
     *
     * <p>One transaction: conditionally move the task to SUCCEEDED, write the media pointers and the
     * READY state, close the attempt, append the result event to the outbox and decrement the
     * capacity counter. Zero affected rows means a stale execution whose result must not be
     * retried into the row.
     */
    boolean complete(TaskLease lease, PublishedArtifacts artifacts, String attemptId);

    /**
     * Records a failed execution and moves the task to its next state.
     *
     * @param retryDelay how long to wait before the next claim; ignored when {@code terminal}
     * @param terminal   whether the task ends in FAILED rather than RETRY_WAIT
     * @return {@code true} when the write applied to the still-current execution
     */
    boolean fail(TaskLease lease, TaskErrorCode errorCode, String errorSummary, Integer exitCode,
                 Duration retryDelay, boolean terminal);

    /** Records progress for a running execution; ignored when the execution is no longer current. */
    boolean updateProgress(ExecutionIdentity identity, int percent);

    /** Moves WAITING_EVENT to QUEUED for the matching generation; a no-op otherwise. */
    boolean markQueued(String taskId, int generation);

    /**
     * Cancels a task.
     *
     * <p>Sets CANCELLED, increments the epoch so an in-flight execution can no longer publish,
     * clears the lease, releases the capacity reservation and appends the result event. The worker
     * discovers the loss of its lease on its next renewal and terminates its child process; it is
     * never allowed to keep publishing just because its process has not stopped yet.
     *
     * @return {@code true} when the task moved to CANCELLED, {@code false} when it was already terminal
     */
    boolean cancel(String taskId, String reason);

    /**
     * Starts a new generation of a terminal task.
     *
     * @return the new generation, or empty when the task is not in a retryable state or its media
     *         was deleted
     */
    Optional<Integer> retry(String taskId, String requestId);

    /**
     * Tasks still marked RUNNING whose lease lapsed.
     *
     * <p>Recovery is driven from the application layer rather than inside this query, so that a
     * recovered execution goes through exactly the same failure path as one that reported its own
     * error: same classification, same attempt bookkeeping, same result event.
     */
    List<ProcessingTaskRecord> findExpiredRunning(int limit);

    /** The attempt row this execution opened, still open. */
    Optional<TaskAttemptRecord> findOpenAttempt(String taskId, int generation);

    List<TaskAttemptRecord> listAttempts(String taskId, int offset, int limit);

    long countAttempts(String taskId);

    /** Closes an attempt row that ended without a task-state change, such as a lost lease. */
    boolean finishAttempt(String attemptId, String state, TaskErrorCode errorCode, String summary,
                          Integer exitCode);

    /** Tasks whose media row was deleted while the task was still active. */
    List<ProcessingTaskRecord> findActiveForDeletedMedia(int limit);
}
