package com.mediaworkspace.worker.schedule;

import com.mediaworkspace.application.service.TaskExecutionService;
import com.mediaworkspace.worker.execution.RunningExecutions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the leases of running executions alive, and stops executions that have lost theirs.
 *
 * <p>Separate from the executing thread on purpose. An encode blocks for minutes; a lease that could
 * only be renewed by the thread doing the encoding would expire in the middle of every long task and
 * the task would be handed to a second worker while the first was still writing.
 *
 * <p>A failed renewal is the signal that this execution no longer owns the row - it was cancelled,
 * retried, or recovered by another worker. The response is to cancel the local execution: it sets
 * the flag the encoder polls, which terminates the process tree it started. Continuing would be
 * useless anyway, because the publish gate would reject the result, but it would keep a core busy
 * and leave a partial file behind for longer than necessary.
 */
@Component
public class LeaseRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewalScheduler.class);

    private final TaskExecutionService executionService;
    private final RunningExecutions running;

    public LeaseRenewalScheduler(TaskExecutionService executionService, RunningExecutions running) {
        this.executionService = executionService;
        this.running = running;
    }

    /** Renews every running execution's lease. */
    @Scheduled(fixedDelayString = "${mediaworkspace.worker.renew-interval-ms:5000}")
    public void renewLeases() {
        for (RunningExecutions.Running execution : running.snapshot()) {
            try {
                if (!executionService.renew(execution.lease())) {
                    execution.cancel("the lease is no longer held by this execution");
                    log.info("task {} generation {} lost its lease; asking the encoder to stop",
                            execution.lease().task().id(), execution.lease().task().generation());
                }
            } catch (RuntimeException e) {
                // The database is unreachable, so the lease cannot be renewed. The execution is not
                // cancelled: the row may still be ours once the database returns, and cancelling on
                // a transient error would throw away work that could still be published. If the
                // outage outlasts the lease, the recovery pass will take the task over.
                log.warn("could not renew the lease of task {}: {}",
                        execution.lease().task().id(), e.getMessage());
            }
        }
    }
}
