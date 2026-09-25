package com.mediaworkspace.application.port.transcode;

/**
 * What ffprobe actually found, independent of the file extension.
 *
 * @param containerFormat container name reported by ffprobe
 * @param hasVideoStream whether a video stream exists; a file without one is rejected
 * @param hasAudioStream whether an audio stream exists; optional, and its absence is not an error
 * @param videoCodec     video codec name, or {@code null}
 * @param audioCodec     audio codec name, or {@code null}
 * @param durationMs     container duration in milliseconds, or {@code null} when unreported
 * @param width          video width in pixels, or {@code null}
 * @param height         video height in pixels, or {@code null}
 * @param bitRate        overall bit rate in bits per second, or {@code null}
 */
public record ProbeResult(
        String containerFormat,
        boolean hasVideoStream,
        boolean hasAudioStream,
        String videoCodec,
        String audioCodec,
        Long durationMs,
        Integer width,
        Integer height,
        Long bitRate) {
}
