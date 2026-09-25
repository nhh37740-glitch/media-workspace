package com.mediaworkspace.contracts.event;

/**
 * Marker for the {@code payload} member of an {@link EventEnvelope}.
 *
 * <p>Payload members deliberately never carry absolute file paths, storage keys or media bytes:
 * a consumer resolves authoritative state from the database using the ids in the envelope.
 */
public sealed interface EventPayload
        permits TaskRequestedPayload, TaskSucceededPayload, TaskFailedPayload, TaskCancelledPayload {
}
