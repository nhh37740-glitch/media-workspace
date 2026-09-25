package com.mediaworkspace.application.port.transcode;

/**
 * Outcome of a successful encode, already re-validated by ffprobe.
 *
 * <p>A zero exit code is necessary but not sufficient. The adapter probes the produced files;
 * this record therefore only exists when the artifacts were confirmed to be a readable MP4 with a
 * video stream and a readable JPEG poster.
 *
 * @param outputFile   absolute path of the produced MP4
 * @param outputBytes  size of the MP4
 * @param posterFile   absolute path of the produced poster
 * @param posterBytes  size of the poster
 * @param durationMs   duration ffprobe reported for the produced MP4
 * @param width        width of the produced video
 * @param height       height of the produced video
 * @param videoCodec   video codec of the produced file
 * @param audioCodec   audio codec of the produced file, or {@code null} when there is no audio
 */
public record TranscodeResult(
        java.nio.file.Path outputFile,
        long outputBytes,
        java.nio.file.Path posterFile,
        long posterBytes,
        long durationMs,
        int width,
        int height,
        String videoCodec,
        String audioCodec) {
}
