package com.mediaworkspace.e2e;

import com.mediaworkspace.application.model.PublishedArtifacts;
import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JOB-02, JOB-03 and JOB-07: one execution owns a task at a time, and a superseded one cannot
 * publish.
 *
 * <p>The situation these tests reproduce is the normal consequence of a long encode on a machine
 * that can be paused: a worker stops renewing its lease, another worker legitimately takes the task
 * over, and the first one then wakes up and tries to write its result. Everything about the design
 * exists to make that write harmless - the output paths are per execution, the publish is a
 * conditional update on the full execution identity, and a zero row count means "discard", never
 * "retry".
 */
class StaleExecutionIT {

    private static E2eSupport support;

    private String workspaceId;
    private String uploaderId;

    @BeforeAll
    static void start() {
        support = E2eSupport.start("job03");
    }

    @AfterAll
    static void stop() {
        if (support != null) {
            support.close();
        }
    }

    @BeforeEach
    void fixture() {
        uploaderId = support.newUser("uploader");
        workspaceId = support.newWorkspace(uploaderId);
    }

    private PublishedArtifacts artifactsOf(String tag) {
        return new PublishedArtifacts("derived/" + tag + "/output.mp4", "derived/" + tag + "/poster.jpg",
                1024, 10_000, 640, 360);
    }

    @Test
    @DisplayName("two workers cannot hold the same task: the second claim finds nothing")
    void onlyOneWorkerHoldsATask() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "contended");
        support.queueDirectly(fixture.taskId());

        Optional<TaskLease> first = support.tasks.claim("worker-a", Duration.ofSeconds(30));
        assertThat(first).isPresent();
        assertThat(first.get().task().id()).isEqualTo(fixture.taskId());

        // The task is now RUNNING and leased, so nothing is due for the second worker.
        Optional<TaskLease> second = support.tasks.claim("worker-b", Duration.ofSeconds(30));
        assertThat(second)
                .as("a task with a live lease must not be handed to a second worker")
                .isEmpty();
    }

    @Test
    @DisplayName("a worker whose lease lapsed cannot publish, and the newer result stands")
    void staleExecutionCannotPublish() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "stale");
        support.queueDirectly(fixture.taskId());

        TaskLease staleLease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        // Worker A stops renewing. In the running system this is a process that was suspended, or a
        // machine that lost its clock; here the lease is simply moved into the past.
        support.expireLease(fixture.taskId());

        // Recovery notices the lapse and puts the task back in a claimable state.
        int recovered = new com.mediaworkspace.application.service.TaskRecoveryService(
                support.tasks, new java.util.Random(1)).recoverLapsedLeases(10);
        assertThat(recovered).isEqualTo(1);
        assertThat(support.taskState(fixture.taskId())).isEqualTo("RETRY_WAIT");

        // Worker B picks it up and publishes.
        support.database().jdbc().update(
                "UPDATE processing_task SET next_run_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                        + "WHERE id = ?", fixture.taskId());
        TaskLease freshLease = support.tasks.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(freshLease.task().executionEpoch())
                .as("a new claim must have a new epoch, so the two executions are distinguishable")
                .isGreaterThan(staleLease.task().executionEpoch());
        assertThat(support.tasks.complete(freshLease, artifactsOf("worker-b"), freshLease.attemptId()))
                .isTrue();
        assertThat(support.mediaStatus(fixture.mediaId())).isEqualTo("READY");
        assertThat(support.mediaOutputKey(fixture.mediaId())).contains("worker-b");

        // A now tries to publish the result it produced while it thought it was still the owner.
        boolean staleAccepted = support.tasks.complete(staleLease, artifactsOf("worker-a"),
                staleLease.attemptId());

        assertThat(staleAccepted)
                .as("the completion must be rejected, not retried")
                .isFalse();
        assertThat(support.mediaOutputKey(fixture.mediaId()))
                .as("the published pointer must still be the newer execution's artifact")
                .contains("worker-b");
        assertThat(support.taskState(fixture.taskId())).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("a stale worker cannot record a failure over a task that already succeeded")
    void staleExecutionCannotFailTheTask() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "stale-failure");
        support.queueDirectly(fixture.taskId());

        TaskLease staleLease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        support.expireLease(fixture.taskId());
        support.database().jdbc().update(
                "UPDATE processing_task SET lease_until = NULL, state = 'QUEUED', worker_id = NULL "
                        + "WHERE id = ?", fixture.taskId());

        TaskLease freshLease = support.tasks.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(support.tasks.complete(freshLease, artifactsOf("b"), freshLease.attemptId())).isTrue();

        // The old execution reports a timeout it suffered while suspended.
        boolean applied = support.tasks.fail(staleLease, TaskErrorCode.PROCESS_TIMEOUT, "timed out",
                null, Duration.ofSeconds(2), false);

        assertThat(applied).isFalse();
        assertThat(support.taskState(fixture.taskId()))
                .as("a superseded execution must not be able to move a finished task")
                .isEqualTo("SUCCEEDED");
        assertThat(support.mediaStatus(fixture.mediaId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("a task whose worker vanished does not stay RUNNING forever")
    void lapsedLeaseIsRecovered() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "vanished");
        support.queueDirectly(fixture.taskId());
        support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        support.expireLease(fixture.taskId());

        int recovered = new com.mediaworkspace.application.service.TaskRecoveryService(
                support.tasks, new java.util.Random(2)).recoverLapsedLeases(10);

        assertThat(recovered).isEqualTo(1);
        assertThat(support.taskState(fixture.taskId()))
                .as("the task must leave RUNNING once its lease lapsed")
                .isEqualTo("RETRY_WAIT");
        // The attempt is recorded as LOST rather than failed: a lapsed lease says the worker stopped
        // talking, not that it produced nothing.
        String attemptState = support.database().jdbc().queryForObject(
                "SELECT state FROM task_attempt WHERE task_id = ? ORDER BY attempt DESC LIMIT 1",
                String.class, fixture.taskId());
        assertThat(attemptState).isEqualTo("LOST");
    }
}
