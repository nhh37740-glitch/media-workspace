package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.OutboxRecord;

import java.time.Instant;
import java.util.List;

/**
 * The transactional outbox.
 *
 * <p>{@link #append} runs inside the caller's business transaction, so an event exists exactly
 * when the state change that produced it exists. Delivery is a separate, retryable step: the
 * publisher sends outside any transaction and only marks the row afterwards, which means a crash
 * between the broker acknowledgement and the mark produces a duplicate rather than a loss.
 */
public interface OutboxRepository {

    /** Appends an event in the caller's current transaction. This never talks to Kafka. */
    void append(OutboxRecord record);

    /**
     * Claims a batch of pending rows for one publisher instance.
     *
     * @param claimToken unique token of this claim; only this token may mark the rows published
     * @return rows now owned by {@code claimToken}
     */
    List<OutboxRecord> claimBatch(String claimToken, int limit, Instant now, Instant claimUntil);

    /**
     * Marks a row published.
     *
     * @return {@code false} when the claim token no longer matches, meaning another publisher has
     *         taken the row over; the caller must not treat it as its own success
     */
    boolean markPublished(String eventId, String claimToken, Instant now);

    /** Reschedules a row after a delivery failure. The record is never dropped. */
    void reschedule(String eventId, String claimToken, Instant nextRunAt);

    /** Returns an expired claim to the pending pool so another publisher can pick it up. */
    int releaseExpiredClaims(Instant now);
}
