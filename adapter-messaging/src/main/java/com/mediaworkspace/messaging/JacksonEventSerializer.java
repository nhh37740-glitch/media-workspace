package com.mediaworkspace.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventPayload;
import com.mediaworkspace.contracts.event.EventType;
import com.mediaworkspace.contracts.event.TaskCancelledPayload;
import com.mediaworkspace.contracts.event.TaskFailedPayload;
import com.mediaworkspace.contracts.event.TaskRequestedPayload;
import com.mediaworkspace.contracts.event.TaskSucceededPayload;
import com.mediaworkspace.contracts.model.TranscodePreset;

import java.time.Instant;

/**
 * JSON codec for the event envelope.
 *
 * <p>Two settings carry contract meaning:
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} is <b>off</b> for reading. A producer running a later
 *       build may add members to a {@code schemaVersion=1} record, and an older consumer must keep
 *       working. Required members are still enforced, by the explicit checks below.</li>
 *   <li>Timestamps are written as ISO-8601 text, not as epoch numbers, because the contract fixes
 *       {@code occurredAt} as a date-time string.</li>
 * </ul>
 *
 * <p>The payload type is chosen from {@code eventType} rather than from a type discriminator inside
 * the payload, which is what the published schema specifies.
 */
public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper mapper;

    public JacksonEventSerializer() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Exposed so tests can assert the exact bytes a producer would put on the wire. */
    public ObjectMapper mapper() {
        return mapper;
    }

    @Override
    public String toJson(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize event " + envelope.eventId(), e);
        }
    }

    @Override
    public EventEnvelope fromJson(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new MalformedEventException("the record is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedEventException("the record is not a JSON object");
        }
        EventType type = readEventType(root);
        int schemaVersion = requireInt(root, "schemaVersion");
        String eventId = requireText(root, "eventId");
        String mediaId = requireText(root, "mediaId");
        String taskId = requireText(root, "taskId");
        int generation = requireInt(root, "generation");
        long aggregateVersion = requireLong(root, "aggregateVersion");
        String requestId = requireText(root, "requestId");
        String traceId = requireText(root, "traceId");
        String producerInvocationId = requireText(root, "producerInvocationId");
        Instant occurredAt = readInstant(root);
        String causationEventId = root.path("causationEventId").isTextual()
                ? root.path("causationEventId").asText() : null;
        EventPayload payload = readPayload(type, root.path("payload"));
        return new EventEnvelope(eventId, type, schemaVersion, occurredAt, mediaId, taskId, generation,
                aggregateVersion, requestId, traceId, producerInvocationId, causationEventId, payload);
    }

    private EventType readEventType(JsonNode root) {
        String raw = requireText(root, "eventType");
        try {
            return EventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("unknown eventType: " + raw);
        }
    }

    private EventPayload readPayload(EventType type, JsonNode payload) {
        if (!payload.isObject()) {
            throw new MalformedEventException("payload is missing or not an object");
        }
        try {
            return switch (type) {
                case TASK_REQUESTED -> new TaskRequestedPayload(
                        TranscodePreset.valueOf(requireText(payload, "preset")));
                case TASK_SUCCEEDED -> new TaskSucceededPayload(
                        requireText(payload, "attemptId"),
                        requireLong(payload, "outputBytes"),
                        requireLong(payload, "durationMs"));
                case TASK_FAILED -> new TaskFailedPayload(
                        requireText(payload, "attemptId"),
                        requireText(payload, "errorCode"),
                        payload.path("retryable").asBoolean(false));
                case TASK_CANCELLED -> new TaskCancelledPayload(
                        TaskCancelledPayload.CancelReason.valueOf(requireText(payload, "reason")));
            };
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("payload does not match " + type + ": " + e.getMessage(), e);
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new MalformedEventException("required member is missing or empty: " + field);
        }
        return value.asText();
    }

    private static int requireInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new MalformedEventException("required numeric member is missing: " + field);
        }
        return value.asInt();
    }

    private static long requireLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new MalformedEventException("required numeric member is missing: " + field);
        }
        return value.asLong();
    }

    private static Instant readInstant(JsonNode root) {
        String raw = requireText(root, "occurredAt");
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw new MalformedEventException("occurredAt is not an ISO-8601 instant: " + raw, e);
        }
    }
}
