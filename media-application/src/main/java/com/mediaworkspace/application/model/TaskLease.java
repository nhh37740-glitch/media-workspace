package com.mediaworkspace.application.model;

import java.time.Instant;

/**
 * A task claimed by a worker, with the lease that authorizes it to publish.
 *
 * @param task       the claimed task row as of the claim
 * @param attemptId  identifier of the attempt row opened by this claim
 * @param workerId   instance id of the claiming worker
 * @param leaseUntil deadline; publishing after this instant requires a successful renewal
 */
public record TaskLease(ProcessingTaskRecord task, String attemptId, String workerId, Instant leaseUntil) {

    public ExecutionIdentity identity() {
        return new ExecutionIdentity(
                task.id(), task.generation(), task.executionEpoch(), workerId);
    }
}
