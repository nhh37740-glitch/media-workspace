package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.OutboxRecord;
import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import com.mediaworkspace.contracts.event.EventTopics;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Isolates a record the consumer cannot interpret.
 *
 * <p>The poison row and the dead-letter outbox record are written in <b>one transaction</b>, and the
 * caller acknowledges the original offset only after that transaction commits. Doing it the other
 * way round - acknowledge, then try to publish a dead letter - means a broker outage at the wrong
 * moment loses the record silently, which is the failure this path exists to prevent.
 *
 * <p>The record is never retried in place. A malformed body or an unsupported schema version does
 * not become parseable by being read again, so retrying would only stall the partition behind it.
 */
public class DeadLetterService {

    /** How much of the original body is copied into the dead-letter record, for diagnosis. */
    static final int MAX_BODY_EXCERPT = 1024;

    private final InboxRepository inbox;
    private final OutboxRepository outbox;
    private final Clock clock;

    public DeadLetterService(InboxRepository inbox, OutboxRepository outbox, Clock clock) {
        this.inbox = inbox;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Records a record as unroutable.
     *
     * <p>Idempotent by coordinates: a redelivery of the same partition and offset finds the poison
     * row already present and appends no second dead-letter record.
     *
     * @param topic       physical topic the record came from
     * @param partition   source partition
     * @param offset      source offset
     * @param errorCode   short code describing why it could not be handled
     * @param bodyHash    hash of the raw body, matching the poison row
     * @param bodyExcerpt bounded excerpt of the raw body, truncated here
     * @return whether this call recorded the record
     */
    @Transactional
    public boolean isolate(String topic, int partition, long offset, String errorCode, String bodyHash,
                           String bodyExcerpt) {
        if (inbox.isPoisonRecorded(topic, partition, offset)) {
            return false;
        }
        inbox.recordPoison(topic, partition, offset, errorCode, bodyHash, clock.instant());
        appendDeadLetter(topic, partition, offset, errorCode, bodyHash, bodyExcerpt);
        return true;
    }

    /**
     * Appends the dead-letter record.
     *
     * <p>The body is a plain diagnostic object rather than an {@link EventEnvelope}: the envelope's
     * {@code eventType} is a closed set of task lifecycle events, and a record that could not be
     * parsed as one of those is by definition not one of those. Inventing an envelope shape for it
     * would put a message on the topic that no schema describes.
     */
    private void appendDeadLetter(String topic, int partition, long offset, String errorCode,
                                  String bodyHash, String bodyExcerpt) {
        Instant now = clock.instant();
        String eventId = UUID.randomUUID().toString();
        String body = "{\"sourceTopic\":" + quote(topic)
                + ",\"sourcePartition\":" + partition
                + ",\"sourceOffset\":" + offset
                + ",\"errorCode\":" + quote(errorCode)
                + ",\"bodyHash\":" + quote(bodyHash)
                + ",\"occurredAt\":" + quote(now.toString())
                + ",\"bodyExcerpt\":" + quote(excerpt(bodyExcerpt)) + "}";
        outbox.append(new OutboxRecord(
                eventId, EventTopics.EVENTS_DLQ, topic + "-" + partition, body,
                "PENDING", 0, now, null, null, null));
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    /** Bounds the excerpt so a large body cannot be copied wholesale into the dead-letter record. */
    static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_EXCERPT ? body : body.substring(0, MAX_BODY_EXCERPT);
    }
}
