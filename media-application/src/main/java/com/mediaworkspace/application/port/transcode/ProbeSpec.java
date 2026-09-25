package com.mediaworkspace.application.port.transcode;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Input to an ffprobe run.
 *
 * @param input                absolute path of the file to inspect
 * @param timeout              wall-clock limit for the probe
 * @param maxStandardOutputBytes hard cap on captured stdout; exceeding it is a failure rather
 *                             than an unbounded allocation
 */
public record ProbeSpec(Path input, Duration timeout, long maxStandardOutputBytes) {

    /** Contract default: 15 seconds, 1 MiB of JSON. */
    public static ProbeSpec of(Path input) {
        return new ProbeSpec(input, Duration.ofSeconds(15), 1024 * 1024);
    }
}
