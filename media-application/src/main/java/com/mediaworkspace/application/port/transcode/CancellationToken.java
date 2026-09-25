package com.mediaworkspace.application.port.transcode;

/**
 * Cooperative cancellation signal handed to a media operation.
 *
 * <p>The adapter polls this while it drains the child's pipes. When it turns true, the adapter
 * terminates the process tree it started. Cancellation is never delivered as a thread interrupt:
 * the executing thread must always be able to finish writing its own diagnostics.
 */
public interface CancellationToken {

    boolean isCancelled();

    /** A token that is never cancelled, for callers with nothing to cancel. */
    static CancellationToken none() {
        return () -> false;
    }
}
