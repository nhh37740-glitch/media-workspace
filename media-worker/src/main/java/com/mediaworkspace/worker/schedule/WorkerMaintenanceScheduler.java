package com.mediaworkspace.worker.schedule;

import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.service.OutboxPublisherService;
import com.mediaworkspace.application.service.StorageMaintenanceService;
import com.mediaworkspace.application.service.TaskRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodic maintenance owned by the worker.
 *
 * <p>Three jobs, all idempotent, so a pass interrupted by a restart is simply run again:
 * <ul>
 *   <li>outbox delivery, so a stopped API does not strand result events;</li>
 *   <li>recovery of tasks whose worker disappeared;</li>
 *   <li>collection of storage the database no longer references.</li>
 * </ul>
 *
 * <p>The intervals are coarse on purpose. Every pass is a database query, and this host also runs
 * MySQL, a broker and the API.
 */
@Component
public class WorkerMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerMaintenanceScheduler.class);

    private static final int RECOVERY_BATCH = 20;
    private static final int SWEEP_BATCH = 200;

    private final OutboxPublisherService outboxPublisher;
    private final TaskRecoveryService recovery;
    private final StorageMaintenanceService storageMaintenance;
    private final TaskRepository tasks;

    private final int outboxBatchSize;

    public WorkerMaintenanceScheduler(OutboxPublisherService outboxPublisher, TaskRecoveryService recovery,
                                      StorageMaintenanceService storageMaintenance, TaskRepository tasks,
                                      @org.springframework.beans.factory.annotation.Value(
                                              "${mediaworkspace.worker.outbox-batch-size:25}")
                                      int outboxBatchSize) {
        this.outboxPublisher = outboxPublisher;
        this.recovery = recovery;
        this.storageMaintenance = storageMaintenance;
        this.tasks = tasks;
        this.outboxBatchSize = outboxBatchSize;
    }

    /** Delivers outbox records. Runs here and in the API; the claim token makes that safe. */
    @Scheduled(fixedDelayString = "${mediaworkspace.worker.outbox-interval-ms:1000}")
    public void publishOutbox() {
        outboxPublisher.releaseExpiredClaims();
        int published = outboxPublisher.publishPending(outboxBatchSize);
        if (published > 0) {
            log.debug("published {} outbox record(s)", published);
        }
    }

    /**
     * Cancels tasks whose media was deleted while they were still active.
     *
     * <p>Deletion already cancels the task in the same transaction. This is the safety net for the
     * window where the API died between marking the media deleted and cancelling the task, or where
     * a task was created by a finalizer that had read the media before the deletion committed.
     */
    @Scheduled(fixedDelayString = "${mediaworkspace.worker.orphan-task-interval-ms:30000}")
    public void cancelOrphanedTasks() {
        List<ProcessingTaskRecord> orphans = tasks.findActiveForDeletedMedia(RECOVERY_BATCH);
        for (ProcessingTaskRecord task : orphans) {
            if (tasks.cancel(task.id(), "MEDIA_DELETED")) {
                log.info("cancelled task {} because its media was deleted", task.id());
            }
        }
    }

    /** Recovers tasks whose lease lapsed, so a crashed worker does not leave them RUNNING forever. */
    @Scheduled(fixedDelayString = "${mediaworkspace.worker.recovery-interval-ms:15000}")
    public void recoverLapsedLeases() {
        recovery.recoverLapsedLeases(RECOVERY_BATCH);
    }

    /**
     * Collects unreferenced storage.
     *
     * <p>Deliberately infrequent. The grace period it applies is a day, so running it often would
     * only spend queries confirming that nothing is old enough yet.
     */
    @Scheduled(fixedDelayString = "${mediaworkspace.worker.sweep-interval-ms:600000}",
            initialDelayString = "${mediaworkspace.worker.sweep-initial-delay-ms:60000}")
    public void sweepStorage() {
        storageMaintenance.sweep(SWEEP_BATCH);
    }
}
