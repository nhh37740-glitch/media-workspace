package com.mediaworkspace.application.model;

/**
 * The complete artifact set the publish transaction writes.
 *
 * <p>All four values are produced by the same execution and validated before this record is
 * built; the repository writes them in one transaction together with the task and media state.
 *
 * @param outputKey  storage-relative key of the produced MP4
 * @param posterKey  storage-relative key of the produced poster
 * @param outputBytes size of the MP4
 * @param durationMs media duration of the produced file
 * @param width      width of the produced video
 * @param height     height of the produced video
 */
public record PublishedArtifacts(
        String outputKey,
        String posterKey,
        long outputBytes,
        long durationMs,
        int width,
        int height) {
}
