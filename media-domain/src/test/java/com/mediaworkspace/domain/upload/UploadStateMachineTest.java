package com.mediaworkspace.domain.upload;

import com.mediaworkspace.contracts.model.UploadState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** UP-05, UP-06, UP-10: the upload lifecycle only takes the documented paths. */
class UploadStateMachineTest {

    private final UploadStateMachine machine = new UploadStateMachine();

    @Test
    @DisplayName("the happy path is OPEN to FINALIZING to COMPLETED")
    void happyPath() {
        assertThat(machine.next(UploadState.OPEN, UploadStateMachine.Action.COMPLETE_REQUESTED))
                .contains(UploadState.FINALIZING);
        assertThat(machine.next(UploadState.FINALIZING, UploadStateMachine.Action.MERGE_PUBLISHED))
                .contains(UploadState.COMPLETED);
    }

    @Test
    @DisplayName("an OPEN session can expire or be aborted, a FINALIZING one cannot")
    void expiryOnlyFromOpen() {
        assertThat(machine.next(UploadState.OPEN, UploadStateMachine.Action.EXPIRE))
                .contains(UploadState.EXPIRED);
        assertThat(machine.next(UploadState.FINALIZING, UploadStateMachine.Action.EXPIRE)).isEmpty();
    }

    @Test
    @DisplayName("a failed merge is FAILED, which is abortable but not completable again")
    void failedMergePath() {
        assertThat(machine.next(UploadState.FINALIZING, UploadStateMachine.Action.MERGE_FAILED))
                .contains(UploadState.FAILED);
        assertThat(machine.next(UploadState.FAILED, UploadStateMachine.Action.ABORT))
                .contains(UploadState.ABORTED);
        assertThat(machine.next(UploadState.FAILED, UploadStateMachine.Action.COMPLETE_REQUESTED)).isEmpty();
        assertThat(machine.next(UploadState.FAILED, UploadStateMachine.Action.MERGE_FAILED)).isEmpty();
    }

    @Test
    @DisplayName("a completed session never moves again, not even to abort")
    void completedIsAbsorbing() {
        for (UploadStateMachine.Action action : UploadStateMachine.Action.values()) {
            assertThat(machine.next(UploadState.COMPLETED, action)).as("%s", action).isEmpty();
        }
    }

    @Test
    @DisplayName("an expired session cannot be revived by a late complete request")
    void expiredIsAbsorbing() {
        for (UploadStateMachine.Action action : UploadStateMachine.Action.values()) {
            assertThat(machine.next(UploadState.EXPIRED, action)).as("%s", action).isEmpty();
        }
    }

    @Test
    @DisplayName("only OPEN and FINALIZING hold a quota reservation")
    void reservationHoldingStates() {
        assertThat(UploadState.OPEN.holdsReservation()).isTrue();
        assertThat(UploadState.FINALIZING.holdsReservation()).isTrue();
        assertThat(UploadState.COMPLETED.holdsReservation()).isFalse();
        assertThat(UploadState.FAILED.holdsReservation()).isFalse();
        assertThat(UploadState.EXPIRED.holdsReservation()).isFalse();
        assertThat(UploadState.ABORTED.holdsReservation()).isFalse();
    }

    @Test
    @DisplayName("state changes are refused from FINALIZING to ABORTED, protecting an in-flight merge")
    void finalizingCannotBeAborted() {
        assertThat(machine.isAllowed(UploadState.FINALIZING, UploadStateMachine.Action.ABORT)).isFalse();
    }
}
