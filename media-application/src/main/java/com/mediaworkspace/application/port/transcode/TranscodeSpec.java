package com.mediaworkspace.application.port.transcode;

import com.mediaworkspace.contracts.model.TranscodePreset;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Input to one encoding run.
 *
 * <p>Every path is per-attempt: the output directory contains the generation and the execution
 * epoch, so a superseded execution can only ever write into its own directory and a retry cannot
 * overwrite the artifacts of the attempt that failed.
 *
 * @param input            absolute path of the immutable original
 * @param outputFile       absolute path the MP4 is written to
 * @param posterFile       absolute path the JPEG poster is written to
 * @param preset           server-side encoding preset; the source never influences codec choice
 * @param sourceDurationMs duration ffprobe reported for the input, used to detect silent truncation
 * @param deadline         wall-clock limit for the whole run
 * @param maxCpuThreads    upper bound on encoder threads
 * @param keepStandardErrorBytes how much of the tail of stderr is retained for diagnosis
 */
public record TranscodeSpec(
        Path input,
        Path outputFile,
        Path posterFile,
        TranscodePreset preset,
        long sourceDurationMs,
        Duration deadline,
        int maxCpuThreads,
        long keepStandardErrorBytes) {

    /**
     * Shortfall beyond which a produced file is treated as truncated rather than merely imprecise.
     *
     * <p>A straight transcode of an intact source reproduces its duration to within a frame. A
     * materially shorter result means the source was damaged and FFmpeg salvaged the part it could
     * decode: it exits successfully and writes a playable file, so the exit code alone cannot tell
     * the difference, and publishing that file would silently drop the user's content.
     */
    public static final double TRUNCATION_TOLERANCE = 0.90;

    /** Shortfall below which the difference is not worth failing over, in milliseconds. */
    public static final long TRUNCATION_FLOOR_MS = 1000;

    /** Contract default: 30 minutes, 2 encoder threads, 64 KiB of retained stderr. */
    public static TranscodeSpec of(Path input, Path outputFile, Path posterFile, TranscodePreset preset,
                                   long sourceDurationMs) {
        return new TranscodeSpec(input, outputFile, posterFile, preset, sourceDurationMs,
                Duration.ofMinutes(30), 2, 64 * 1024);
    }

    /** Whether a produced duration is short enough to indicate that content was lost. */
    public boolean isTruncated(long producedDurationMs) {
        if (sourceDurationMs <= 0) {
            return false;
        }
        long shortfall = sourceDurationMs - producedDurationMs;
        return shortfall > TRUNCATION_FLOOR_MS
                && producedDurationMs < sourceDurationMs * TRUNCATION_TOLERANCE;
    }
}
