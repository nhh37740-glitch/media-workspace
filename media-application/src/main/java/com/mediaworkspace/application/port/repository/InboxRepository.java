package com.mediaworkspace.application.port.repository;

import java.time.Instant;

/**
 * Consumer-side deduplication and poison handling.
 *
 * <p>The inbox insert and the business update share one transaction. The offset is committed only
 * after that transaction commits, so a redelivery finds the inbox row and performs no second
 * state change. A record whose body differs from a previously seen id with the same event id is a
 * conflict, not a duplicate, and is isolated.
 */
public interface InboxRepository {

    /** Outcome of trying to record an event as processed. */
    enum IntakeResult {
        /** First time this event id is seen for this consumer group; process it. */
        NEW,
        /** Already processed with an identical body; skip the business update. */
        DUPLICATE,
        /** Already processed with a different body; isolate and do not apply. */
        CONFLICT
    }

    /**
     * Records an event as processed in the caller's transaction.
     *
     * <p>Only a duplicate-key violation is treated as "already present". Any other SQL failure
     * propagates, because swallowing it would turn a broken transaction into a silent skip.
     */
    IntakeResult intake(String consumerGroup, String eventId, String bodyHash, Instant now);

    /** Records a record that cannot be parsed or whose schema version is unsupported. */
    void recordPoison(String topic, int partition, long offset, String errorCode, String bodyHash,
                      Instant now);

    /** Whether a poison record for this coordinate already exists. */
    boolean isPoisonRecorded(String topic, int partition, long offset);
}
