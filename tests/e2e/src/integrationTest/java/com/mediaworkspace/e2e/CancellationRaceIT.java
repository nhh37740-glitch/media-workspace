package com.mediaworkspace.e2e;

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
 * JOB-05 and JOB-08: a cancel and a publish that meet each other resolve to exactly one outcome.
 *
 * <p>This is the race the whole lease design exists for. A user cancels while the worker is in the
 * middle of an encode; the worker finishes and tries to publish. Whichever transaction takes the
 * task row lock first decides, and the other must lose cleanly - a cancelled task must not become
 * READY, and a successful task must not be cancelled out from under a user who is watching it.
 *
 * <p>The assertions are made on the row after both transactions have completed, because the point is
 * the outcome, not which of the two won.
 */
class CancellationRaceIT {

    private static E2eSupport support;

    private String workspaceId;
    private String uploaderId;

    @BeforeAll
    static void start() {
        support = E2eSupport.start("job05");
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

    private PublishedArtifacts artifacts() {
        return new PublishedArtifacts("derived/race/output.mp4", "derived/race/poster.jpg",
                2048, 8_000, 480, 270);
    }

    @Test
    @DisplayName("a cancel that commits first keeps the task CANCELLED and publishes nothing")
    void cancelWinsTheRace() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "cancel-wins");
        support.queueDirectly(fixture.taskId());
        TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(support.tasks.cancel(fixture.taskId(), "USER_REQUEST")).isTrue();

        // The encode finishes anyway and the worker tries to publish what it produced.
        boolean published = support.tasks.complete(lease, artifacts(), lease.attemptId());

        assertThat(published).as("a cancelled task must not accept a result").isFalse();
        assertThat(support.taskState(fixture.taskId())).isEqualTo("CANCELLED");
        assertThat(support.mediaStatus(fixture.mediaId()))
                .as("a cancelled task must not leave the media READY")
                .isEqualTo("CANCELLED");
        assertThat(support.mediaOutputKey(fixture.mediaId()))
                .as("no file pointer may be published for the cancelled execution")
                .isNull();
    }

    @Test
    @DisplayName("a publish that commits first makes a later cancel a conflict, not a silent revert")
    void publishWinsTheRace() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "publish-wins");
        support.queueDirectly(fixture.taskId());
        TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(support.tasks.complete(lease, artifacts(), lease.attemptId())).isTrue();
        // The cancel arrives afterwards. It must not be able to undo a finished task.
        assertThat(support.tasks.cancel(fixture.taskId(), "USER_REQUEST")).isFalse();

        assertThat(support.taskState(fixture.taskId())).isEqualTo("SUCCEEDED");
        assertThat(support.mediaStatus(fixture.mediaId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("a cancel and a publish started together produce one legal outcome")
    void concurrentCancelAndPublish() throws Exception {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "concurrent");
        support.queueDirectly(fixture.taskId());
        TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();

        // Both transactions begin before either commits, so the row lock decides the order. The
        // barrier does not guarantee which wins, which is the point: the assertion is that the
        // result is one of the two legal end states and never a mixture.
        CountDownLatch ready = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> cancel = pool.submit(() -> {
                ready.countDown();
                ready.await(10, TimeUnit.SECONDS);
                return support.tasks.cancel(fixture.taskId(), "USER_REQUEST");
            });
            Future<Boolean> publish = pool.submit(() -> {
                ready.countDown();
                ready.await(10, TimeUnit.SECONDS);
                return support.tasks.complete(lease, artifacts(), lease.attemptId());
            });

            boolean cancelled = cancel.get(30, TimeUnit.SECONDS);
            boolean published = publish.get(30, TimeUnit.SECONDS);

            assertThat(cancelled && published)
                    .as("both cannot have applied: one of the two transactions must have lost")
                    .isFalse();
            assertThat(cancelled || published)
                    .as("exactly one of the two must have applied")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        String state = support.taskState(fixture.taskId());
        String mediaState = support.mediaStatus(fixture.mediaId());
        if ("SUCCEEDED".equals(state)) {
            assertThat(mediaState).isEqualTo("READY");
        } else {
            assertThat(state).isEqualTo("CANCELLED");
            assertThat(mediaState).isEqualTo("CANCELLED");
        }
    }

    @Test
    @DisplayName("deleting the media cancels its unfinished task and the old result is refused")
    void deletingTheMediaCancelsTheTask() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "deleted");
        support.queueDirectly(fixture.taskId());
        TaskLease lease = support.tasks.claim("worker-a", Duration.ofSeconds(30)).orElseThrow();

        // The deletion shadows the media and cancels the task, which is what the delete use case does
        // in one transaction.
        assertThat(support.media.markDeleted(fixture.mediaId())).isTrue();
        assertThat(support.tasks.cancel(fixture.taskId(), "MEDIA_DELETED")).isTrue();

        // The worker had already finished; its result must not resurrect the media.
        assertThat(support.tasks.complete(lease, artifacts(), lease.attemptId())).isFalse();
        assertThat(support.taskState(fixture.taskId())).isEqualTo("CANCELLED");
        assertThat(support.media.findVisible(fixture.mediaId()))
                .as("a deleted media must stay invisible to every read path")
                .isEmpty();
    }
}
