package com.mediaworkspace.e2e;

import com.mediaworkspace.application.service.TaskIntakeService;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventType;
import com.mediaworkspace.contracts.event.TaskRequestedPayload;
import com.mediaworkspace.contracts.model.TranscodePreset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MSG-01: the same request event delivered ten times produces one task transition.
 *
 * <p>Kafka delivers at least once. A broker restart, a rebalance after a slow consumer, or a retry
 * after a failed acknowledgement all produce a duplicate. The system must therefore be built so a
 * duplicate is a no-op, and this is where that is asserted: the intake records the event in the
 * inbox and moves the task, in one transaction, and only the first delivery finds the task in
 * WAITING_EVENT.
 *
 * <p>Scope: the test drives the intake path directly rather than through a broker, because the
 * property under test is the deduplication, not the transport. The transport is exercised by the
 * deployed system's smoke test.
 */
class DuplicateRequestEventIT {

    private static E2eSupport support;
    private static TaskIntakeService intake;

    private String workspaceId;
    private String uploaderId;

    @BeforeAll
    static void start() {
        support = E2eSupport.start("msg01");
        intake = new TaskIntakeService(support.inbox, support.tasks, Clock.systemUTC());
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

    private EventEnvelope requestedEvent(E2eSupport.TaskFixture fixture) {
        return new EventEnvelope(UUID.randomUUID().toString(), EventType.TASK_REQUESTED,
                EventEnvelope.SCHEMA_VERSION, Instant.now(), fixture.mediaId(), fixture.taskId(),
                1, 1L, UUID.randomUUID().toString(), fixture.traceId(),
                UUID.randomUUID().toString(), null,
                new TaskRequestedPayload(TranscodePreset.MP4_720P_V1));
    }

    @Test
    @DisplayName("ten deliveries of one event produce one inbox row and one transition")
    void duplicatesAreAbsorbed() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "duplicate");
        EventEnvelope envelope = requestedEvent(fixture);
        String bodyHash = IdempotencyKeys.sha256Hex("the record body");

        TaskIntakeService.IntakeOutcome first = intake.apply(envelope, bodyHash, "media-worker-v1");
        assertThat(first).isEqualTo(TaskIntakeService.IntakeOutcome.QUEUED);
        long versionAfterFirst = support.taskVersion(fixture.taskId());

        for (int delivery = 2; delivery <= 10; delivery++) {
            TaskIntakeService.IntakeOutcome outcome = intake.apply(envelope, bodyHash, "media-worker-v1");
            assertThat(outcome)
                    .as("delivery %d must be recognised as a duplicate", delivery)
                    .isEqualTo(TaskIntakeService.IntakeOutcome.DUPLICATE);
        }

        assertThat(support.inboxCount("media-worker-v1", envelope.eventId())).isEqualTo(1);
        assertThat(support.taskState(fixture.taskId())).isEqualTo("QUEUED");
        assertThat(support.taskVersion(fixture.taskId()))
                .as("the repeated deliveries must not keep bumping the row version")
                .isEqualTo(versionAfterFirst);
    }

    @Test
    @DisplayName("a later event for the same task leaves it QUEUED once and does not re-transition")
    void aSecondDistinctEventDoesNotRequeue() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "requeue");
        EventEnvelope firstEvent = requestedEvent(fixture);
        assertThat(intake.apply(firstEvent, IdempotencyKeys.sha256Hex("body-a"), "media-worker-v1"))
                .isEqualTo(TaskIntakeService.IntakeOutcome.QUEUED);

        // A different event id for the same task is not a duplicate of the first, so it is processed
        // and found to be past WAITING_EVENT. It must not move the task a second time.
        EventEnvelope secondEvent = requestedEvent(fixture);
        assertThat(intake.apply(secondEvent, IdempotencyKeys.sha256Hex("body-b"), "media-worker-v1"))
                .isEqualTo(TaskIntakeService.IntakeOutcome.IGNORED);

        assertThat(support.taskState(fixture.taskId())).isEqualTo("QUEUED");
        // Both records are remembered, so a redelivery of either stays a no-op.
        assertThat(support.inboxCount("media-worker-v1", firstEvent.eventId())).isEqualTo(1);
        assertThat(support.inboxCount("media-worker-v1", secondEvent.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("an event for a cancelled task is recorded and ignored, not applied")
    void cancelledTaskIsNotRequeued() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "cancelled");
        assertThat(support.tasks.cancel(fixture.taskId(), "USER_REQUEST")).isTrue();

        EventEnvelope late = requestedEvent(fixture);
        assertThat(intake.apply(late, IdempotencyKeys.sha256Hex("late"), "media-worker-v1"))
                .isEqualTo(TaskIntakeService.IntakeOutcome.IGNORED);

        assertThat(support.taskState(fixture.taskId()))
                .as("a cancel that won the race must not be undone by a late event")
                .isEqualTo("CANCELLED");
        assertThat(support.inboxCount("media-worker-v1", late.eventId()))
                .as("the record is still remembered so a redelivery stays a no-op")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same event id with a different body is isolated, not applied")
    void conflictingBodyIsIsolated() {
        E2eSupport.TaskFixture fixture = support.newTask(workspaceId, uploaderId, "conflict");
        EventEnvelope envelope = requestedEvent(fixture);
        String eventId = envelope.eventId();

        assertThat(intake.apply(envelope, IdempotencyKeys.sha256Hex("original"), "media-worker-v1"))
                .isEqualTo(TaskIntakeService.IntakeOutcome.QUEUED);

        // Same id, different bytes. This cannot be a duplicate, and applying it would mean acting on
        // a record nobody sent, so it is recorded and dropped.
        assertThat(intake.apply(envelope, IdempotencyKeys.sha256Hex("tampered"), "media-worker-v1"))
                .isEqualTo(TaskIntakeService.IntakeOutcome.IGNORED);

        assertThat(support.inboxCount("media-worker-v1", eventId)).isEqualTo(1);
        assertThat(support.taskState(fixture.taskId())).isEqualTo("QUEUED");
    }
}
