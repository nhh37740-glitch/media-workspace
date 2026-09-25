package com.mediaworkspace.contracts.model;

/**
 * State of a multipart upload session.
 *
 * <pre>
 * OPEN -&gt; FINALIZING -&gt; COMPLETED
 * OPEN -&gt; EXPIRED | ABORTED
 * FINALIZING -&gt; FAILED
 * FAILED -&gt; ABORTED
 * </pre>
 */
public enum UploadState {
    /** Accepting chunks. */
    OPEN,
    /** All chunks are present; a finalizer holds (or is waiting for) the merge lease. */
    FINALIZING,
    /** The original file was published, a media row and a task exist. */
    COMPLETED,
    /** Merge or full-file hash verification failed. The quota reservation was released once. */
    FAILED,
    /** An OPEN session passed its 24 hour deadline. */
    EXPIRED,
    /** Terminated by the uploader or an owner. */
    ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED || this == ABORTED;
    }

    /** True while the session still holds a source-quota reservation. */
    public boolean holdsReservation() {
        return this == OPEN || this == FINALIZING;
    }
}
