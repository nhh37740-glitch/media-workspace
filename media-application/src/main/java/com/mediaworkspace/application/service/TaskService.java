package com.mediaworkspace.application.service;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.IdempotencyRecord;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.model.TaskAttemptRecord;
import com.mediaworkspace.application.port.repository.IdempotencyRepository;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.application.support.JsonCodec;
import com.mediaworkspace.contracts.dto.CancelTaskResponse;
import com.mediaworkspace.contracts.dto.PageResponse;
import com.mediaworkspace.contracts.dto.RetryTaskResponse;
import com.mediaworkspace.contracts.dto.TaskAttemptView;
import com.mediaworkspace.contracts.dto.TaskView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.contracts.model.TaskState;
import com.mediaworkspace.domain.access.RolePolicy;
import com.mediaworkspace.domain.access.SpaceAction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Task queries, cancellation and retry.
 *
 * <p>Cancelling and retrying are state transitions guarded by the task row lock, so a cancel that
 * races a successful publish resolves to exactly one legal outcome. Retry starts a new generation
 * on the same task rather than creating a second task, which is why the attempt history survives.
 */
public class TaskService {

    /** Route key used to scope retry idempotency records. */
    public static final String RETRY_ROUTE = "POST /tasks/{taskId}/retry";
    /** Route key used to scope cancel records, which are naturally idempotent by state. */
    public static final String CANCEL_ROUTE = "POST /tasks/{taskId}/cancel";

    private final TaskRepository tasks;
    private final MediaRepository media;
    private final WorkspaceRepository workspaces;
    private final IdempotencyRepository idempotency;
    private final RolePolicy rolePolicy = new RolePolicy();
    private final Clock clock;

    public TaskService(TaskRepository tasks, MediaRepository media, WorkspaceRepository workspaces,
                       IdempotencyRepository idempotency, Clock clock) {
        this.tasks = tasks;
        this.media = media;
        this.workspaces = workspaces;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TaskView get(String actorId, String taskId) {
        return toView(requireVisible(actorId, taskId, SpaceAction.VIEW_MEDIA));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskAttemptView> attempts(String actorId, String taskId, int page, int pageSize) {
        requireVisible(actorId, taskId, SpaceAction.VIEW_MEDIA);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MediaService.MAX_PAGE_SIZE, Math.max(1, pageSize));
        List<TaskAttemptView> items = tasks.listAttempts(taskId, (safePage - 1) * safeSize, safeSize).stream()
                .map(TaskService::toAttemptView)
                .toList();
        return PageResponse.of(items, safePage, safeSize, tasks.countAttempts(taskId));
    }

    /**
     * Cancels a task.
     *
     * <p>Repeating a cancel returns the same answer rather than a conflict, because the caller's
     * intent is already satisfied. Cancelling a task that already succeeded or failed is a genuine
     * conflict: the outcome is decided and cannot be undone.
     */
    @Transactional
    public CancelTaskResponse cancel(String actorId, String taskId) {
        ProcessingTaskRecord task = requireVisible(actorId, taskId, SpaceAction.CONTROL_TASK);
        if (task.state().isTerminal()) {
            throw ApplicationException.conflict(
                    "a task that already reached a terminal state cannot be cancelled", taskId);
        }
        cancelInternal(taskId, "USER_REQUEST");
        return new CancelTaskResponse(taskId, TaskState.CANCELLED.name());
    }

    /**
     * Cancels a task from inside another use case that already holds the relevant locks.
     *
     * <p>Used by media deletion. It reports no error when the task is already terminal: a deletion
     * that races a successful publish must still succeed.
     */
    @Transactional
    public boolean cancelInternal(String taskId, String reason) {
        return tasks.cancel(taskId, reason);
    }

    /**
     * Starts a new generation of a failed or cancelled task.
     *
     * <p>Retrying is idempotent under a key: a replay returns the generation that was started the
     * first time instead of incrementing again, which is what keeps a retried HTTP request from
     * burning a generation.
     */
    @Transactional
    public RetryTaskResponse retry(String actorId, String taskId, String idempotencyKey,
                                   String requestHash, String requestId) {
        Optional<IdempotencyRecord> existing = idempotency.find(
                actorId, RETRY_ROUTE, taskId, IdempotencyKeys.sha256Hex(idempotencyKey));
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!IdempotencyKeys.constantTimeEquals(record.requestHash(), requestHash)) {
                throw new ApplicationException(ApiErrorCode.IDEMPOTENCY_CONFLICT,
                        "this idempotency key was already used with a different request body", taskId);
            }
            return JsonCodec.read(record.responseJson(), RetryTaskResponse.class);
        }

        ProcessingTaskRecord task = requireVisible(actorId, taskId, SpaceAction.CONTROL_TASK);
        if (!task.state().isTerminal() || task.state() == TaskState.SUCCEEDED) {
            throw ApplicationException.conflict(
                    "only a failed or cancelled task can be retried", taskId);
        }
        MediaRecord target = media.findVisible(task.mediaId())
                .orElseThrow(() -> ApplicationException.notFound("media not found", task.mediaId()));
        if (!rolePolicy.allows(workspaces.roleOf(target.workspaceId(), actorId).orElse(null),
                SpaceAction.CONTROL_TASK)) {
            throw ApplicationException.notFound("task not found", taskId);
        }
        int generation = tasks.retry(taskId, requestId)
                .orElseThrow(() -> ApplicationException.conflict(
                        "the task could not start a new generation", taskId));
        RetryTaskResponse response = new RetryTaskResponse(taskId, generation, TaskState.WAITING_EVENT.name());
        idempotency.insert(new IdempotencyRecord(actorId, RETRY_ROUTE, taskId,
                IdempotencyKeys.sha256Hex(idempotencyKey), requestHash, JsonCodec.write(response), 202));
        return response;
    }

    private ProcessingTaskRecord requireVisible(String actorId, String taskId, SpaceAction action) {
        ProcessingTaskRecord task = tasks.findById(taskId)
                .orElseThrow(() -> ApplicationException.notFound("task not found", taskId));
        MediaRecord target = media.findVisible(task.mediaId())
                .orElseThrow(() -> ApplicationException.notFound("task not found", taskId));
        Role role = workspaces.roleOf(target.workspaceId(), actorId).orElse(null);
        if (!rolePolicy.isVisible(role)) {
            throw ApplicationException.notFound("task not found", taskId);
        }
        if (!rolePolicy.allows(role, action)) {
            throw ApplicationException.forbidden("role does not permit this action", taskId);
        }
        return task;
    }

    private TaskView toView(ProcessingTaskRecord task) {
        return new TaskView(task.id(), task.mediaId(), task.state().name(), task.generation(),
                task.attempt(), task.progress(), task.errorCode(), task.version(),
                task.updatedAt().toString());
    }

    private static TaskAttemptView toAttemptView(TaskAttemptRecord attempt) {
        return new TaskAttemptView(
                attempt.id(), attempt.attempt(), attempt.generation(), attempt.state(),
                attempt.startedAt() == null ? null : attempt.startedAt().toString(),
                attempt.finishedAt() == null ? null : attempt.finishedAt().toString(),
                attempt.errorCode(), attempt.errorSummary());
    }
}
