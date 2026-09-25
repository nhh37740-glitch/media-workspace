package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.model.TaskAttemptRecord;
import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import com.mediaworkspace.domain.task.BackoffPolicy;
import com.mediaworkspace.domain.task.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Recovers tasks whose worker disappeared.
 *
 * <p>A worker that is killed cannot report anything, so its task stays RUNNING with a lease nobody
 * renews. This pass finds those rows, closes their attempt as LOST, and moves the task to RETRY_WAIT
 * or FAILED through the same repository call a reported failure uses - same attempt accounting, same
 * result event. A crash and an exception therefore do not become two different kinds of failure in
 * the data.
 *
 * <p>Its deliberate limit: a lapsed lease says only that the worker stopped talking. It does not say
 * whether that worker wrote a file. The attempt is recorded as LOST rather than failed, no claim is
 * made about the filesystem, and any partial output is left to the delayed collector.
 */
public class TaskRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);

    private final TaskRepository tasks;
    private final BackoffPolicy backoff;

    public TaskRecoveryService(TaskRepository tasks, RandomGenerator random) {
        this.tasks = tasks;
        this.backoff = new BackoffPolicy(random);
    }

    /**
     * Recovers tasks whose lease lapsed.
     *
     * @return how many tasks were moved
     */
    @Transactional
    public int recoverLapsedLeases(int limit) {
        List<ProcessingTaskRecord> lapsed = tasks.findExpiredRunning(limit);
        int recovered = 0;
        for (ProcessingTaskRecord task : lapsed) {
            if (recoverOne(task)) {
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("recovered {} task(s) whose worker stopped reporting", recovered);
        }
        return recovered;
    }

    private boolean recoverOne(ProcessingTaskRecord task) {
        Optional<TaskAttemptRecord> openAttempt = tasks.findOpenAttempt(task.id(), task.generation());

        // The lease already lapsed, so the identity in the row is stale by definition. It is passed
        // through unchanged rather than replaced, because the conditional update must still match
        // the exact execution this recovery read: if a newer execution claimed the row meanwhile,
        // the update matches nothing and the recovery correctly does not apply.
        TaskLease lease = new TaskLease(task,
                openAttempt.map(TaskAttemptRecord::id).orElse(syntheticAttemptId(task)),
                task.workerId() == null ? "" : task.workerId(), task.leaseUntil());

        boolean terminal = TaskStateMachine.MAX_ATTEMPTS_PER_GENERATION <= task.attempt();
        Duration delay = terminal ? Duration.ZERO : backoff.delayAfter(task.attempt());

        // recoverLapsed, not fail. The ordinary failure path requires a live lease because an
        // execution may only write while it owns the row; recovery runs precisely because the lease
        // is gone, so that condition made every recovery a no-op and left the task RUNNING with
        // nobody working on it. This is the defect the JOB-07 case exists to catch.
        if (!tasks.recoverLapsed(lease, TaskErrorCode.WORKER_LOST,
                "the executing worker stopped reporting", delay, terminal)) {
            return false;
        }
        log.info("task {} generation {} attempt {} was recovered as {}",
                task.id(), task.generation(), task.attempt(), terminal ? "FAILED" : "RETRY_WAIT");
        return true;
    }

    /**
     * A stand-in attempt id for the case where the attempt row is already closed.
     *
     * <p>Closing an already-closed attempt is a no-op, so the value only has to be non-null for the
     * call to be well formed; it is never stored.
     */
    private String syntheticAttemptId(ProcessingTaskRecord task) {
        return task.id() + ":" + task.generation() + ":" + task.executionEpoch();
    }
}
