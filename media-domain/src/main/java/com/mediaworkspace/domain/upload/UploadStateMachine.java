package com.mediaworkspace.domain.upload;

import com.mediaworkspace.contracts.model.UploadState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Legal transitions of an upload session.
 *
 * <pre>
 * OPEN       -&gt; FINALIZING | EXPIRED | ABORTED
 * FINALIZING -&gt; COMPLETED  | FAILED
 * FAILED     -&gt; ABORTED
 * </pre>
 *
 * <p>FINALIZING is not reachable from EXPIRED, and an expired cleanup must never delete a session
 * that a finalizer currently owns.
 */
public final class UploadStateMachine {

    /** Action applied to an upload session. */
    public enum Action {
        /** All chunks are present; the session moves to merge. */
        COMPLETE_REQUESTED,
        /** The merge published the original file and created the media row. */
        MERGE_PUBLISHED,
        /** The merge or the whole-file hash check failed. */
        MERGE_FAILED,
        /** The 24 hour deadline passed while the session was OPEN. */
        EXPIRE,
        /** The uploader or an owner terminated the session. */
        ABORT
    }

    private static final Map<UploadState, Map<Action, UploadState>> TRANSITIONS = new EnumMap<>(UploadState.class);

    static {
        Map<Action, UploadState> open = new EnumMap<>(Action.class);
        open.put(Action.COMPLETE_REQUESTED, UploadState.FINALIZING);
        open.put(Action.EXPIRE, UploadState.EXPIRED);
        open.put(Action.ABORT, UploadState.ABORTED);
        TRANSITIONS.put(UploadState.OPEN, open);

        Map<Action, UploadState> finalizing = new EnumMap<>(Action.class);
        finalizing.put(Action.MERGE_PUBLISHED, UploadState.COMPLETED);
        finalizing.put(Action.MERGE_FAILED, UploadState.FAILED);
        TRANSITIONS.put(UploadState.FINALIZING, finalizing);

        Map<Action, UploadState> failed = new EnumMap<>(Action.class);
        failed.put(Action.ABORT, UploadState.ABORTED);
        TRANSITIONS.put(UploadState.FAILED, failed);

        TRANSITIONS.put(UploadState.COMPLETED, Map.of());
        TRANSITIONS.put(UploadState.EXPIRED, Map.of());
        TRANSITIONS.put(UploadState.ABORTED, Map.of());
    }

    public Optional<UploadState> next(UploadState state, Action action) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(state, Map.of()).get(action));
    }

    public boolean isAllowed(UploadState state, Action action) {
        return next(state, action).isPresent();
    }

    /** States whose stored chunks a garbage collector may consider unreferenced. */
    public Set<UploadState> statesReleasingChunks() {
        return EnumSet.of(UploadState.COMPLETED, UploadState.FAILED, UploadState.EXPIRED, UploadState.ABORTED);
    }
}
