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
 * @param deadline         wall-clock limit for the whole run
 * @param maxCpuThreads    upper bound on encoder threads
 * @param keepStandardErrorBytes how much of the tail of stderr is retained for diagnosis
 */
public record TranscodeSpec(
        Path input,
        Path outputFile,
        Path posterFile,
        TranscodePreset preset,
        Duration deadline,
        int maxCpuThreads,
        long keepStandardErrorBytes) {

    /** Contract default: 30 minutes, 2 encoder threads, 64 KiB of retained stderr. */
    public static TranscodeSpec of(Path input, Path outputFile, Path posterFile, TranscodePreset preset) {
        return new TranscodeSpec(input, outputFile, posterFile, preset,
                Duration.ofMinutes(30), 2, 64 * 1024);
    }
}
