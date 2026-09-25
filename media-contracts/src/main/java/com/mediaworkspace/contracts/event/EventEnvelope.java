package com.mediaworkspace.contracts.event;

import java.time.Instant;

/**
 * Envelope shared by every media task event.
 *
 * <p>Wire rules from the contract: {@code schemaVersion} is {@code 1} for this baseline; unknown
 * <em>additional</em> members are tolerated for {@code schemaVersion=1}, required members are not;
 * an unknown major version is routed to the dead-letter topic instead of retried forever. The
 * serialized form stays under 16 KiB and never contains file bytes, absolute paths or secrets.
 *
 * @param eventId              unique id of this event record; the outbox dedup key
 * @param eventType            which payload this envelope carries
 * @param schemaVersion        envelope schema version, currently {@link #SCHEMA_VERSION}
 * @param occurredAt           when the producing transaction committed
 * @param mediaId              media this event concerns
 * @param taskId               task this event concerns
 * @param generation           task generation; retries increment it
 * @param aggregateVersion     task row version at production time, used to reject stale results
 * @param requestId            originating HTTP request, or a fresh id for recovery-generated work
 * @param traceId              business chain id, inherited from the persisted task
 * @param producerInvocationId invocation that produced this record
 * @param causationEventId     the event that caused this one, or {@code null}
 * @param payload              type-specific body
 */
public record EventEnvelope(
        String eventId,
        EventType eventType,
        int schemaVersion,
        Instant occurredAt,
        String mediaId,
        String taskId,
        int generation,
        long aggregateVersion,
        String requestId,
        String traceId,
        String producerInvocationId,
        String causationEventId,
        EventPayload payload) {

    /** Envelope schema version implemented by this baseline. */
    public static final int SCHEMA_VERSION = 1;

    public EventEnvelope {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }

    public boolean isKnownSchemaVersion() {
        return schemaVersion == SCHEMA_VERSION;
    }
}
