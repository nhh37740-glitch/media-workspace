package com.mediaworkspace.application.port.transcode;

/**
 * In-process progress callback from the transcoder to the code that drives it.
 *
 * <p>This is one of three distinct "done" signals in the system, and the names are kept apart on
 * purpose: this listener reports encoding progress inside one JVM; a Kafka producer callback
 * reports that a record was acknowledged by the broker; and neither of them means the media task
 * succeeded. Only the publish transaction does.
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * Called as the encoder reports progress.
     *
     * @param percent 0..99; the caller clamps and rate-limits before persisting
     */
    void onProgress(int percent);

    /** A listener that ignores progress, used when the caller has nowhere to record it. */
    static ProgressListener noop() {
        return percent -> {
        };
    }
}
