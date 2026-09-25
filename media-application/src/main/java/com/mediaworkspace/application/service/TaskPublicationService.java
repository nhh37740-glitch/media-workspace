package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.PublishedArtifacts;
import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * The transactional half of finishing an execution.
 *
 * <p>A separate bean from {@link TaskExecutionService} for a concrete reason: that service runs an
 * encoder for minutes and must not hold a database transaction while it does. Calling a
 * {@code @Transactional} method on the same object would be a self-invocation that Spring silently
 * ignores, so the task row, the media row, the attempt row, the capacity counter and the outbox
 * record would each be written in their own transaction - leaving, for example, a media marked READY
 * whose task still says RUNNING if the process died between the two.
 *
 * <p>Both methods return whether the write applied. {@code false} means the execution was superseded
 * and its result must be discarded, never written again.
 */
public class TaskPublicationService {

    private final TaskRepository tasks;

    public TaskPublicationService(TaskRepository tasks) {
        this.tasks = tasks;
    }

    /**
     * Publishes a validated artifact set.
     *
     * <p>One transaction for the task state, the media pointers, the attempt record, the capacity
     * release and the result event.
     */
    @Transactional
    public boolean publishSuccess(TaskLease lease, PublishedArtifacts artifacts, String attemptId) {
        return tasks.complete(lease, artifacts, attemptId);
    }

    /**
     * Records a failed execution.
     *
     * @param retryDelay how long before the task may be claimed again; ignored when terminal
     * @param terminal   whether this failure ends the task
     */
    @Transactional
    public boolean recordFailure(TaskLease lease, TaskErrorCode errorCode, String summary,
                                 Integer exitCode, Duration retryDelay, boolean terminal) {
        return tasks.fail(lease, errorCode, summary, exitCode, retryDelay, terminal);
    }

    /**
     * Cancels a task that has no media left to process.
     *
     * <p>Separate from the user-facing cancel because it is reached from inside an execution, where
     * the caller has already observed that the media row is gone.
     */
    @Transactional
    public boolean cancelForDeletedMedia(String taskId) {
        return tasks.cancel(taskId, "MEDIA_DELETED");
    }

    /** Records a progress value for a running execution. */
    @Transactional
    public boolean recordProgress(com.mediaworkspace.application.model.ExecutionIdentity identity,
                                  int percent) {
        return tasks.updateProgress(identity, percent);
    }
}
