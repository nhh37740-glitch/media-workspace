package com.mediaworkspace.contracts.event;

/**
 * Body of {@code TASK_SUCCEEDED}.
 *
 * @param attemptId   identifier of the attempt that produced the artifact
 * @param outputBytes size of the published MP4
 * @param durationMs  media duration reported by ffprobe for the produced file
 */
public record TaskSucceededPayload(String attemptId, long outputBytes, long durationMs) implements EventPayload {
}
