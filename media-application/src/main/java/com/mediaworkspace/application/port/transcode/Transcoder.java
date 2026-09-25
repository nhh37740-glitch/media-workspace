package com.mediaworkspace.application.port.transcode;

/**
 * Runs the external media binaries.
 *
 * <p>Implementations start child processes from an argument list rather than a shell string, drain
 * both output pipes concurrently, enforce per-phase deadlines, and terminate only the process tree
 * they started. No other module in the system starts a media process.
 */
public interface Transcoder {

    /**
     * Inspects a file and reports what it actually contains.
     *
     * @param spec   input path and limits
     * @param cancel cooperative cancellation
     * @return probe outcome
     * @throws TranscodeException with {@code INVALID_MEDIA} when the file cannot be probed, and
     *                            {@code UNSUPPORTED_MEDIA} when it has no video stream
     */
    ProbeResult probe(ProbeSpec spec, CancellationToken cancel);

    /**
     * Encodes the input and produces a poster.
     *
     * @param spec     paths, preset and limits
     * @param progress in-process progress callback
     * @param cancel   cooperative cancellation; the adapter kills its own child on cancellation
     * @return validated artifacts
     * @throws TranscodeException classified failure; {@code PROCESS_TIMEOUT} when the deadline
     *                            passed, {@code PROCESS_START_FAILED} when the binary could not be
     *                            launched, {@code DISK_FULL} when the volume filled up
     */
    TranscodeResult execute(TranscodeSpec spec, ProgressListener progress, CancellationToken cancel);
}
