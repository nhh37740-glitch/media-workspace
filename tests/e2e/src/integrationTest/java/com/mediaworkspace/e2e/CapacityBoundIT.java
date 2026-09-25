package com.mediaworkspace.e2e;

import com.mediaworkspace.application.model.CapacitySnapshot;
import com.mediaworkspace.application.model.PublishedArtifacts;
import com.mediaworkspace.application.model.TaskLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOAD-01 and JOB-09: the backlog is bounded by a counter in the database, not by memory.
 *
 * <p>The limit is enforced by locking one row in the same transaction that creates or terminates a
 * task, which is what makes it exact under concurrency. Two finalizers that reach the boundary at
 * the same instant cannot both find the last slot: they serialize on the row.
 *
 * <p>The counter is also checked for drift against the task table. A counter that silently disagrees
 * with reality is worse than no counter, because it would either admit more work than configured or
 * refuse work that would fit - and the check reports disagreement rather than repairing it, since a
 * silent repair hides the bug that caused it.
 */
class CapacityBoundIT {

    private static E2eSupport support;

    private String workspaceId;
    private String uploaderId;

    @BeforeAll
    static void start() {
        support = E2eSupport.start("load01");
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
        support.setCapacityMax(100);
    }

    @Test
    @DisplayName("the counter is incremented when a task is created and released when it ends")
    void counterTracksTheTaskLifecycle() {
        support.setCapacityMax(5);
        int before = support.capacityCount();

        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "counted");
        assertThat(support.capacityCount()).isEqualTo(before + 1);

        support.queueDirectly(fixture.taskId());
        TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        assertThat(support.tasks.complete(lease, new PublishedArtifacts("k/o.mp4", "k/p.jpg", 1, 1, 2, 2),
                lease.attemptId())).isTrue();

        assertThat(support.capacityCount())
                .as("a terminal task must release its slot exactly once")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the counter never exceeds its limit under concurrent admission")
    void concurrentAdmissionRespectsTheLimit() throws Exception {
        support.setCapacityMax(3);
        CountDownLatch ready = new CountDownLatch(6);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Future<?>[] futures = new Future<?>[6];
            for (int i = 0; i < 6; i++) {
                final int index = i;
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    ready.await(15, TimeUnit.SECONDS);
                    // Each caller uses its own session and therefore its own connection, which is
                    // what makes the row lock meaningful: a shared connection would serialize the
                    // callers in the test harness and prove nothing about the database. A caller
                    // that read the counter without locking it is the bug this test exists to catch.
                    try (E2eSupport.RepositoryBundle bundle = support.newRepositories()) {
                        CapacitySnapshot counter = bundle.capacity.lock("processing").orElse(null);
                        if (counter != null && counter.hasFreeSlot()) {
                            support.newTask(bundle, workspaceId, uploaderId, "concurrent-" + index);
                        }
                    }
                    return null;
                });
            }
            for (Future<?> future : futures) {
                future.get(40, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(support.capacityCount())
                .as("the limit must hold even when every caller checks at the same instant")
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a retry at the capacity limit is refused rather than admitting a second execution")
    void retryIsRefusedAtTheLimit() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "retry-limit");
        support.queueDirectly(fixture.taskId());
        TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
        support.tasks.fail(lease, com.mediaworkspace.contracts.model.TaskErrorCode.INVALID_MEDIA,
                "not a video", null, Duration.ZERO, true);
        assertThat(support.taskState(fixture.taskId())).isEqualTo("FAILED");
        assertThat(support.capacityCount()).isZero();

        // Refusing the retry is an answer, not an error: the caller is told there is no room.
        support.database().jdbc().update(
                "UPDATE capacity_counter SET active_count = max_count WHERE name = 'processing'");
        assertThat(support.tasks.retry(fixture.taskId(), java.util.UUID.randomUUID().toString()))
                .as("a retry must not be admitted when the counter is full")
                .isEmpty();
        assertThat(support.taskState(fixture.taskId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("the counter is reported as agreeing with the task table")
    void driftCheckAgrees() {
        support.setCapacityMax(100);
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "drift");
        support.queueDirectly(fixture.taskId());

        var drift = support.capacity.checkDrift("processing");
        assertThat(drift.agrees())
                .as("counter %d vs task table %d", drift.counterValue(), drift.actualValue())
                .isTrue();
    }

    @Test
    @DisplayName("a disagreement between the counter and the task table is reported, not repaired")
    void driftIsReported() {
        support.setCapacityMax(100);
        // The counter is moved behind the table's back. In production this stands for a bug that
        // released a slot twice; the check must surface it rather than quietly rewriting the row.
        support.database().jdbc().update(
                "UPDATE capacity_counter SET active_count = active_count + 7 WHERE name = 'processing'");

        var drift = support.capacity.checkDrift("processing");

        assertThat(drift.agrees()).isFalse();
        assertThat(drift.counterValue()).isNotEqualTo(drift.actualValue());
        // Reported, and left alone: repairing it here would erase the evidence.
        assertThat(support.capacityCount()).isEqualTo(drift.counterValue());
    }

    @Test
    @DisplayName("a task at the attempt limit is failed rather than retried forever")
    void attemptBudgetIsExhausted() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "budget");
        support.queueDirectly(fixture.taskId());

        for (int attempt = 1; attempt <= 3; attempt++) {
            TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();
            boolean terminal = attempt == 3;
            assertThat(support.tasks.fail(lease,
                    com.mediaworkspace.contracts.model.TaskErrorCode.PROCESS_TIMEOUT,
                    "timed out", null, terminal ? Duration.ZERO : Duration.ofMillis(50), terminal))
                    .isTrue();
            if (!terminal) {
                assertThat(support.taskState(fixture.taskId())).isEqualTo("RETRY_WAIT");
                support.database().jdbc().update(
                        "UPDATE processing_task SET next_run_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                                + "WHERE id = ?", fixture.taskId());
            }
        }

        assertThat(support.taskState(fixture.taskId())).isEqualTo("FAILED");
        assertThat(support.capacityCount())
                .as("the slot is released once, when the task becomes terminal")
                .isZero();
        assertThat(support.tasks.claim("worker-a", Duration.ofSeconds(30)))
                .as("a failed task is not claimable again without an explicit retry")
                .isEmpty();
    }
}
