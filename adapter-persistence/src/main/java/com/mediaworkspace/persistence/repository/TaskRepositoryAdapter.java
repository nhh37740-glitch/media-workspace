package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.CapacitySnapshot;
import com.mediaworkspace.application.model.ExecutionIdentity;
import com.mediaworkspace.application.model.OutboxRecord;
import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.model.PublishedArtifacts;
import com.mediaworkspace.application.model.TaskAttemptRecord;
import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.port.repository.CapacityRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventPayload;
import com.mediaworkspace.contracts.event.EventTopics;
import com.mediaworkspace.contracts.event.EventType;
import com.mediaworkspace.contracts.event.TaskCancelledPayload;
import com.mediaworkspace.contracts.event.TaskFailedPayload;
import com.mediaworkspace.contracts.event.TaskRequestedPayload;
import com.mediaworkspace.contracts.event.TaskSucceededPayload;
import com.mediaworkspace.contracts.model.MediaState;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import com.mediaworkspace.contracts.model.TaskState;
import com.mediaworkspace.contracts.model.TranscodePreset;
import com.mediaworkspace.persistence.mapper.CapacityMapper;
import com.mediaworkspace.persistence.mapper.MessagingMapper;
import com.mediaworkspace.persistence.mapper.TaskMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis implementation of {@link TaskRepository}.
 *
 * <p>This class performs no transaction management of its own. Each of its methods is written to be
 * one atomic step, and the application-layer use case that calls it owns the boundary. That split is
 * what lets a publish be "task row, media row, attempt row, capacity counter and outbox record, all
 * or nothing" without the persistence layer deciding business policy.
 *
 * <p>Result events are built here because they are written in the same transaction as the state
 * change they describe. No method in this class contacts Kafka: an event becomes visible to the
 * broker only when the outbox publisher later picks the row up.
 */
public class TaskRepositoryAdapter implements TaskRepository {

    private final TaskMapper tasks;
    private final CapacityMapper capacity;
    private final MessagingMapper messaging;
    private final EventSerializer serializer;

    public TaskRepositoryAdapter(TaskMapper tasks, CapacityMapper capacity, MessagingMapper messaging,
                                 EventSerializer serializer) {
        this.tasks = tasks;
        this.capacity = capacity;
        this.messaging = messaging;
        this.serializer = serializer;
    }

    @Override
    public void insert(String taskId, String mediaId, String traceId, String preset, String requestId) {
        tasks.insert(taskId, mediaId, traceId, preset);
    }

    @Override
    public Optional<ProcessingTaskRecord> findById(String taskId) {
        return Optional.ofNullable(tasks.findById(taskId));
    }

    @Override
    public Optional<ProcessingTaskRecord> findByMediaId(String mediaId) {
        return Optional.ofNullable(tasks.findByMediaId(mediaId));
    }

    @Override
    public Optional<ProcessingTaskRecord> lockById(String taskId) {
        return Optional.ofNullable(tasks.lockById(taskId));
    }

    /**
     * Claims a task.
     *
     * <p>Three statements in the caller's transaction. The first locks a due row with
     * {@code SKIP LOCKED}, so concurrent workers take different rows instead of queueing; the second
     * advances the attempt and the epoch; the third opens the attempt record. A zero row count from
     * the second statement means the row stopped being claimable, and the claim is abandoned rather
     * than forced.
     */
    @Override
    public Optional<TaskLease> claim(String workerId, Duration leaseDuration) {
        ProcessingTaskRecord due = tasks.findNextDue();
        if (due == null) {
            return Optional.empty();
        }
        if (tasks.claimLocked(due.id(), workerId, leaseDuration.toSeconds()) == 0) {
            return Optional.empty();
        }
        ProcessingTaskRecord claimed = tasks.findById(due.id());
        if (claimed == null) {
            return Optional.empty();
        }
        String attemptId = UUID.randomUUID().toString();
        tasks.insertAttempt(attemptId, claimed.id(), claimed.generation(), claimed.attempt(),
                claimed.executionEpoch(), workerId);
        return Optional.of(new TaskLease(claimed, attemptId, workerId, claimed.leaseUntil()));
    }

    @Override
    public boolean renew(TaskLease lease, Duration leaseDuration) {
        ExecutionIdentity identity = lease.identity();
        return tasks.renew(identity.taskId(), identity.generation(), identity.executionEpoch(),
                identity.workerId(), leaseDuration.toSeconds()) > 0;
    }

    @Override
    public boolean complete(TaskLease lease, PublishedArtifacts artifacts, String attemptId) {
        ExecutionIdentity identity = lease.identity();
        if (tasks.completeTask(identity.taskId(), identity.generation(), identity.executionEpoch(),
                identity.workerId()) == 0) {
            // A newer execution owns the row. The produced files stay unreferenced on disk and are
            // collected later; the completion is not retried, because retrying could overwrite a
            // newer result.
            return false;
        }
        String mediaId = lease.task().mediaId();
        tasks.updateMediaReady(mediaId, artifacts.outputKey(), artifacts.posterKey(),
                artifacts.outputBytes(), artifacts.durationMs(), artifacts.width(), artifacts.height());
        tasks.finishAttempt(attemptId, "SUCCEEDED", null, null, 0);
        capacity.decrement(CapacityRepository.PROCESSING);
        appendResultEvent(lease, EventType.TASK_SUCCEEDED, identity.generation(),
                new TaskSucceededPayload(attemptId, artifacts.outputBytes(), artifacts.durationMs()));
        return true;
    }

    @Override
    public boolean fail(TaskLease lease, TaskErrorCode errorCode, String errorSummary, Integer exitCode,
                        Duration retryDelay, boolean terminal) {
        ExecutionIdentity identity = lease.identity();
        String state = terminal ? TaskState.FAILED.name() : TaskState.RETRY_WAIT.name();
        if (tasks.failTask(identity.taskId(), identity.generation(), identity.executionEpoch(),
                identity.workerId(), state, errorCode.name(), retryDelay.toMillis()) == 0) {
            return false;
        }
        tasks.finishAttempt(lease.attemptId(), "FAILED", errorCode.name(), errorSummary, exitCode);
        if (!terminal) {
            // The task will be claimed again; a retryable failure is not a published result.
            return true;
        }
        String mediaId = lease.task().mediaId();
        tasks.updateMediaState(mediaId, MediaState.FAILED.name(), null, null, true);
        capacity.decrement(CapacityRepository.PROCESSING);
        appendResultEvent(lease, EventType.TASK_FAILED, identity.generation(),
                new TaskFailedPayload(lease.attemptId(), errorCode.name(), false));
        return true;
    }

    @Override
    public boolean updateProgress(ExecutionIdentity identity, int percent) {
        return tasks.updateProgress(identity.taskId(), identity.generation(), identity.executionEpoch(),
                identity.workerId(), percent) > 0;
    }

    @Override
    public boolean markQueued(String taskId, int generation) {
        return tasks.markQueued(taskId, generation) > 0;
    }

    /**
     * Cancels a task.
     *
     * <p>The capacity counter is locked first, following the fixed lock order, because cancelling
     * releases a reservation. The task lock follows, so a cancel that races a publish resolves to
     * exactly one legal outcome: whichever transaction takes the row lock first decides.
     */
    @Override
    public boolean cancel(String taskId, String reason) {
        Optional<CapacitySnapshot> counter = Optional.ofNullable(
                capacity.lock(CapacityRepository.PROCESSING));
        if (counter.isEmpty()) {
            return false;
        }
        ProcessingTaskRecord task = tasks.lockById(taskId);
        if (task == null || task.state().isTerminal()) {
            return false;
        }
        if (tasks.cancelTask(taskId, reason) == 0) {
            return false;
        }
        tasks.finishOpenAttempts(taskId, task.generation(), "CANCELLED", reason,
                "cancelled before the execution published");
        tasks.updateMediaState(task.mediaId(), MediaState.CANCELLED.name(), null, null, true);
        capacity.decrement(CapacityRepository.PROCESSING);
        TaskCancelledPayload payload = new TaskCancelledPayload("MEDIA_DELETED".equals(reason)
                ? TaskCancelledPayload.CancelReason.MEDIA_DELETED
                : TaskCancelledPayload.CancelReason.USER_REQUEST);
        appendEvent(task.mediaId(), taskId, task.generation(), task.traceId(),
                UUID.randomUUID().toString(), EventTopics.TASK_RESULT, EventType.TASK_CANCELLED, payload);
        return true;
    }

    /**
     * Starts a new generation.
     *
     * <p>Lock order again puts the capacity counter first, because a retry takes a reservation. The
     * media row is read to confirm it has not been deleted, which is the one condition under which a
     * retry must be refused rather than admitted.
     */
    @Override
    public Optional<Integer> retry(String taskId, String requestId) {
        CapacitySnapshot counter = capacity.lock(CapacityRepository.PROCESSING);
        if (counter == null || !counter.hasFreeSlot()) {
            return Optional.empty();
        }
        ProcessingTaskRecord task = tasks.lockById(taskId);
        if (task == null || (task.state() != TaskState.FAILED && task.state() != TaskState.CANCELLED)) {
            return Optional.empty();
        }
        if (tasks.retryTask(taskId) == 0) {
            return Optional.empty();
        }
        Integer generation = tasks.currentGeneration(taskId);
        if (generation == null) {
            return Optional.empty();
        }
        tasks.updateMediaState(task.mediaId(), MediaState.PROCESSING.name(), null, null, true);
        capacity.increment(CapacityRepository.PROCESSING);
        appendRequestedEvent(task.mediaId(), taskId, generation, task.traceId(), requestId,
                task.preset().name());
        return Optional.of(generation);
    }

    @Override
    public List<ProcessingTaskRecord> findExpiredRunning(int limit) {
        return tasks.findExpiredRunning(limit);
    }

    @Override
    public Optional<TaskAttemptRecord> findOpenAttempt(String taskId, int generation) {
        return Optional.ofNullable(tasks.findOpenAttempt(taskId, generation));
    }

    @Override
    public boolean finishAttempt(String attemptId, String state, TaskErrorCode errorCode, String summary,
                                 Integer exitCode) {
        return tasks.finishAttempt(attemptId, state,
                errorCode == null ? null : errorCode.name(), summary, exitCode) > 0;
    }

    @Override
    public List<TaskAttemptRecord> listAttempts(String taskId, int offset, int limit) {
        return tasks.listAttempts(taskId, offset, limit);
    }

    @Override
    public long countAttempts(String taskId) {
        return tasks.countAttempts(taskId);
    }

    @Override
    public List<ProcessingTaskRecord> findActiveForDeletedMedia(int limit) {
        return tasks.findActiveForDeletedMedia(limit);
    }

    /** Unfinished tasks according to the task table, for the drift check. */
    public int countUnfinishedTasks() {
        return tasks.countUnfinished();
    }

    /**
     * Appends a result event.
     *
     * <p>A result is produced by a worker, not by an HTTP request, so it carries a freshly generated
     * requestId: there is no live request to attribute it to, and reusing the traceId as a requestId
     * would falsely suggest one. The traceId is inherited so the whole chain stays searchable.
     */
    private void appendResultEvent(TaskLease lease, EventType type, int generation, EventPayload payload) {
        appendEvent(lease.task().mediaId(), lease.task().id(), generation, lease.task().traceId(),
                UUID.randomUUID().toString(), EventTopics.TASK_RESULT, type, payload);
    }

    private void appendRequestedEvent(String mediaId, String taskId, int generation, String traceId,
                                      String requestId, String preset) {
        appendEvent(mediaId, taskId, generation, traceId, requestId, EventTopics.TASK_REQUESTED,
                EventType.TASK_REQUESTED, new TaskRequestedPayload(TranscodePreset.valueOf(preset)));
    }

    private void appendEvent(String mediaId, String taskId, int generation, String traceId,
                             String requestId, String topic, EventType type, EventPayload payload) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(), type, EventEnvelope.SCHEMA_VERSION, Instant.now(),
                mediaId, taskId, generation, 1L, requestId, traceId,
                UUID.randomUUID().toString(), null, payload);
        messaging.appendOutbox(new OutboxRecord(
                envelope.eventId(), topic, mediaId, serializer.toJson(envelope),
                "PENDING", 0, Instant.now(), null, null, null));
    }
}
