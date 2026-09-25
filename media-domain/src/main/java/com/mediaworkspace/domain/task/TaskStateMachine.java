package com.mediaworkspace.domain.task;

import com.mediaworkspace.contracts.model.TaskState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The only place that decides whether a task transition is legal.
 *
 * <pre>
 * WAITING_EVENT --REQUEST_EVENT_CONSUMED--&gt; QUEUED
 * QUEUED        --CLAIM------------------&gt; RUNNING
 * RETRY_WAIT    --CLAIM------------------&gt; RUNNING
 * RUNNING       --SUCCEED----------------&gt; SUCCEEDED
 * RUNNING       --RETRYABLE_FAILURE------&gt; RETRY_WAIT, or FAILED when attempts are exhausted
 * RUNNING       --PERMANENT_FAILURE------&gt; FAILED
 * any non-terminal --CANCEL--------------&gt; CANCELLED
 * FAILED/CANCELLED --RETRY_REQUESTED-----&gt; WAITING_EVENT
 * </pre>
 *
 * <p>Attempts are not tracked here: the caller supplies the outcome of the attempt budget so a
 * single place (the domain) decides between {@code RETRY_WAIT} and {@code FAILED}.
 *
 * <p>Terminal states are absorbing: no action moves a task out of SUCCEEDED, and only an explicit
 * retry moves it out of FAILED or CANCELLED.
 */
public final class TaskStateMachine {

    /** Executions allowed per generation before a retryable failure becomes terminal. */
    public static final int MAX_ATTEMPTS_PER_GENERATION = 3;

    private static final Map<TaskState, Set<TaskAction>> ALLOWED = new EnumMap<>(TaskState.class);

    static {
        ALLOWED.put(TaskState.WAITING_EVENT, EnumSet.of(TaskAction.REQUEST_EVENT_CONSUMED, TaskAction.CANCEL));
        ALLOWED.put(TaskState.QUEUED, EnumSet.of(TaskAction.CLAIM, TaskAction.CANCEL));
        ALLOWED.put(TaskState.RUNNING, EnumSet.of(
                TaskAction.SUCCEED, TaskAction.RETRYABLE_FAILURE, TaskAction.PERMANENT_FAILURE, TaskAction.CANCEL));
        ALLOWED.put(TaskState.RETRY_WAIT, EnumSet.of(TaskAction.CLAIM, TaskAction.CANCEL));
        ALLOWED.put(TaskState.SUCCEEDED, EnumSet.noneOf(TaskAction.class));
        ALLOWED.put(TaskState.FAILED, EnumSet.of(TaskAction.RETRY_REQUESTED));
        ALLOWED.put(TaskState.CANCELLED, EnumSet.of(TaskAction.RETRY_REQUESTED));
    }

    /**
     * Whether {@code action} is legal in {@code state}, ignoring attempt accounting.
     *
     * <p>{@code RETRYABLE_FAILURE} from RUNNING is structurally legal; whether it lands in
     * RETRY_WAIT or FAILED depends on the remaining attempt budget.
     */
    public boolean isAllowed(TaskState state, TaskAction action) {
        return ALLOWED.getOrDefault(state, Set.of()).contains(action);
    }

    /**
     * Resolves the target state for an action.
     *
     * @param state            current state
     * @param action           action being applied
     * @param attemptsConsumed executions already started in this generation, including the one
     *                         that just failed
     * @return the next state, or empty when the transition is illegal
     */
    public Optional<TaskState> next(TaskState state, TaskAction action, int attemptsConsumed) {
        if (!isAllowed(state, action)) {
            return Optional.empty();
        }
        return switch (action) {
            case REQUEST_EVENT_CONSUMED -> Optional.of(TaskState.QUEUED);
            case CLAIM -> Optional.of(TaskState.RUNNING);
            case SUCCEED -> Optional.of(TaskState.SUCCEEDED);
            case PERMANENT_FAILURE -> Optional.of(TaskState.FAILED);
            case RETRYABLE_FAILURE -> Optional.of(
                    attemptsConsumed >= MAX_ATTEMPTS_PER_GENERATION ? TaskState.FAILED : TaskState.RETRY_WAIT);
            case CANCEL -> Optional.of(TaskState.CANCELLED);
            case RETRY_REQUESTED -> Optional.of(TaskState.WAITING_EVENT);
        };
    }

    /**
     * Whether a progress value may be written.
     *
     * <p>Progress belongs to a running execution only. A late frame must never overwrite the
     * progress of a terminal task, and reaching 100 is reserved for the publish transaction.
     */
    public boolean acceptsProgress(TaskState state) {
        return state == TaskState.RUNNING;
    }

    /**
     * Whether a claim may start another execution.
     *
     * @param attemptsConsumed executions already started in this generation
     */
    public boolean canClaim(TaskState state, int attemptsConsumed) {
        return (state == TaskState.QUEUED || state == TaskState.RETRY_WAIT)
                && attemptsConsumed < MAX_ATTEMPTS_PER_GENERATION;
    }
}
