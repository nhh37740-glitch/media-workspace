package com.mediaworkspace.contracts.event;

import com.mediaworkspace.contracts.model.TranscodePreset;

/**
 * Body of {@code TASK_REQUESTED}.
 *
 * @param preset encoding preset the worker must apply; the worker reads the source file from the
 *               database using the envelope's {@code taskId}, never from this payload
 */
public record TaskRequestedPayload(TranscodePreset preset) implements EventPayload {
}
