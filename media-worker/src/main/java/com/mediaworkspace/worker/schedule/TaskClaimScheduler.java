package com.mediaworkspace.worker.schedule;

import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.application.service.TaskExecutionService;
import com.mediaworkspace.worker.execution.ExecutionSlotPool;
import com.mediaworkspace.worker.execution.RunningExecutions;
import com.mediaworkspace.worker.execution.WorkerIdentity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Claims due tasks and runs them on this worker's own threads.
 *
 * <p>The order in this class is the whole point: a permit is taken first, then a task is claimed, and
 * the permit is released in a {@code finally}. Reversing it would mean claiming work that cannot be
 * started, which leaves the task in RUNNING with nobody working on it until the lease lapses.
 *
 * <p>The executor is bounded by the pool's capacity, so a slow task cannot make this class create
 * threads: with no free slot nothing is claimed, and the backlog stays in the database.
 */
@Component
public class TaskClaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskClaimScheduler.class);

    private final TaskExecutionService executionService;
    private final ExecutionSlotPool slots;
    private final RunningExecutions running;
    private final WorkerIdentity identity;
    private final ExecutorService executor;

    public TaskClaimScheduler(TaskExecutionService executionService, ExecutionSlotPool slots,
                              RunningExecutions running, WorkerIdentity identity) {
        this.executionService = executionService;
        this.slots = slots;
        this.running = running;
        this.identity = identity;
        // One thread per slot, named so a thread dump shows which slot is stuck.
        this.executor = Executors.newFixedThreadPool(slots.capacity(), runnable -> {
            Thread thread = new Thread(runnable, "mw-worker-slot-" + System.nanoTime() % 100000);
            thread.setDaemon(false);
            return thread;
        });
    }

    /** Claims while there is capacity, then returns so the scheduler can run again. */
    @Scheduled(fixedDelayString = "${mediaworkspace.worker.claim-interval-ms:1000}")
    public void claimAndRun() {
        // Claims at most one task per free slot per tick. The loop ends as soon as the pool is full,
        // so this never queues: it either starts work or stops.
        while (slots.tryAcquire()) {
            Optional<TaskLease> lease = claimOne();
            if (lease.isEmpty()) {
                slots.release();
                return;
            }
            submit(lease.get());
        }
    }

    private Optional<TaskLease> claimOne() {
        try {
            Optional<TaskLease> lease = executionService.claim(identity.instanceId());
            if (lease.isPresent()) {
                TaskLease held = lease.get();
                log.info("claimed task {} generation {} attempt {} epoch {}",
                        held.task().id(), held.task().generation(), held.task().attempt(),
                        held.task().executionEpoch());
            }
            return lease;
        } catch (RuntimeException e) {
            // A database that is unavailable must stop this worker from claiming, not kill it.
            log.warn("claiming failed; this worker will not take work until it recovers: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    private void submit(TaskLease lease) {
        RunningExecutions.Running execution = running.register(lease);
        executor.submit(() -> {
            try {
                TaskExecutionService.ExecutionOutcome outcome =
                        executionService.run(lease, execution.token());
                log.info("task {} generation {} outcome {}",
                        lease.task().id(), lease.task().generation(), outcome);
            } catch (RuntimeException e) {
                // The execution service records classified failures itself. Reaching here means
                // something unforeseen happened; the lease is left to lapse so the task is
                // recovered rather than stuck.
                log.error("unexpected failure while executing task {}", lease.task().id(), e);
            } finally {
                running.unregister(lease.task().id());
                slots.release();
            }
        });
    }

    /**
     * Stops cleanly.
     *
     * <p>In-flight executions are told to cancel, then given a bounded window to terminate their own
     * child processes and write their diagnostics. Anything still running after that is abandoned to
     * the lease recovery: its lease lapses and another worker takes the task over.
     */
    @PreDestroy
    public void shutdown() {
        log.info("worker {} is shutting down; cancelling {} execution(s)",
                identity.shortId(), running.size());
        running.cancelAll("the worker is shutting down");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
