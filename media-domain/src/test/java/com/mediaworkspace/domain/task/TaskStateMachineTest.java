package com.mediaworkspace.domain.task;

import com.mediaworkspace.contracts.model.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** JOB-01: every state/action pair must resolve to exactly the transitions the contract allows. */
class TaskStateMachineTest {

    private final TaskStateMachine machine = new TaskStateMachine();

    @Test
    @DisplayName("the documented happy path is walkable end to end")
    void happyPath() {
        TaskState state = TaskState.WAITING_EVENT;
        state = machine.next(state, TaskAction.REQUEST_EVENT_CONSUMED, 0).orElseThrow();
        assertThat(state).isEqualTo(TaskState.QUEUED);
        state = machine.next(state, TaskAction.CLAIM, 0).orElseThrow();
        assertThat(state).isEqualTo(TaskState.RUNNING);
        state = machine.next(state, TaskAction.SUCCEED, 1).orElseThrow();
        assertThat(state).isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    @DisplayName("a retryable failure returns to RETRY_WAIT while the attempt budget lasts")
    void retryableFailureWaits() {
        for (int consumed : new int[] {1, 2}) {
            assertThat(machine.next(TaskState.RUNNING, TaskAction.RETRYABLE_FAILURE, consumed))
                    .contains(TaskState.RETRY_WAIT);
        }
    }

    @Test
    @DisplayName("the third retryable failure is terminal: attempt budget is three per generation")
    void retryableFailureExhausts() {
        assertThat(machine.next(TaskState.RUNNING, TaskAction.RETRYABLE_FAILURE, 3))
                .contains(TaskState.FAILED);
    }

    @Test
    @DisplayName("a permanent failure fails immediately, whatever the remaining budget")
    void permanentFailureIsImmediate() {
        assertThat(machine.next(TaskState.RUNNING, TaskAction.PERMANENT_FAILURE, 1))
                .contains(TaskState.FAILED);
    }

    @Test
    @DisplayName("RETRY_WAIT is claimable again after backoff")
    void retryWaitIsClaimable() {
        assertThat(machine.next(TaskState.RETRY_WAIT, TaskAction.CLAIM, 1))
                .contains(TaskState.RUNNING);
    }

    @Test
    @DisplayName("only an explicit retry leaves a terminal FAILED or CANCELLED state")
    void terminalStatesOnlyMoveOnRetry() {
        for (TaskState state : EnumSet.of(TaskState.FAILED, TaskState.CANCELLED)) {
            for (TaskAction action : TaskAction.values()) {
                Optional<TaskState> resolved = machine.next(state, action, 0);
                if (action == TaskAction.RETRY_REQUESTED) {
                    assertThat(resolved).contains(TaskState.WAITING_EVENT);
                } else {
                    assertThat(resolved).as("%s / %s", state, action).isEmpty();
                }
            }
        }
    }

    @Test
    @DisplayName("SUCCEEDED is absorbing: nothing moves a succeeded task, not even a retry")
    void succeededIsAbsorbing() {
        for (TaskAction action : TaskAction.values()) {
            assertThat(machine.next(TaskState.SUCCEEDED, action, 0))
                    .as("action %s", action)
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(TaskState.class)
    @DisplayName("cancellation is legal from every non-terminal state and from no terminal one")
    void cancellationReachesEveryActiveState(TaskState state) {
        boolean allowed = machine.isAllowed(state, TaskAction.CANCEL);
        assertThat(allowed).isEqualTo(!state.isTerminal());
    }

    @ParameterizedTest
    @EnumSource(TaskState.class)
    @DisplayName("progress is accepted only while RUNNING, so a terminal task is never overwritten")
    void progressOnlyWhileRunning(TaskState state) {
        assertThat(machine.acceptsProgress(state)).isEqualTo(state == TaskState.RUNNING);
    }

    @Test
    @DisplayName("a task may only be claimed from QUEUED or RETRY_WAIT, and only with budget left")
    void claimPreconditions() {
        assertThat(machine.canClaim(TaskState.QUEUED, 0)).isTrue();
        assertThat(machine.canClaim(TaskState.RETRY_WAIT, 2)).isTrue();
        assertThat(machine.canClaim(TaskState.QUEUED, 3)).isFalse();
        assertThat(machine.canClaim(TaskState.WAITING_EVENT, 0)).isFalse();
        assertThat(machine.canClaim(TaskState.RUNNING, 1)).isFalse();
        assertThat(machine.canClaim(TaskState.SUCCEEDED, 0)).isFalse();
    }

    @Test
    @DisplayName("the full legal transition table is exactly the set the contract documents")
    void exhaustiveTransitionTable() {
        Set<String> legal = new java.util.HashSet<>();
        for (TaskState state : TaskState.values()) {
            for (TaskAction action : TaskAction.values()) {
                machine.next(state, action, 1)
                        .ifPresent(target -> legal.add(state + "->" + target));
            }
        }
        assertThat(legal).containsExactlyInAnyOrder(
                "WAITING_EVENT->QUEUED",
                "WAITING_EVENT->CANCELLED",
                "QUEUED->RUNNING",
                "QUEUED->CANCELLED",
                "RUNNING->SUCCEEDED",
                "RUNNING->RETRY_WAIT",
                "RUNNING->FAILED",
                "RUNNING->CANCELLED",
                "RETRY_WAIT->RUNNING",
                "RETRY_WAIT->CANCELLED",
                "FAILED->WAITING_EVENT",
                "CANCELLED->WAITING_EVENT");
    }
}
