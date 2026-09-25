package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.OutboxRecord;
import com.mediaworkspace.application.port.messaging.EventPublisher;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivers outbox records to the broker.
 *
 * <p>The send happens outside any database transaction. A record is claimed with a token and a
 * lease, sent, and only then acknowledged by a conditional update that requires the same token.
 * That ordering is what makes a crash between the broker acknowledgement and the acknowledgement
 * update produce a duplicate rather than a loss: the row is still pending, so it is sent again,
 * and the consumer's inbox makes the second delivery a no-op.
 *
 * <p>A failed send never deletes the record. It is rescheduled with a backoff, so a broker outage
 * delays events instead of discarding them.
 */
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    /** Claim lease: long enough for a batch to be sent, short enough to recover quickly. */
    static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    /** Base backoff after a failed send; doubled per consecutive failure up to the cap. */
    static final Duration RETRY_BASE = Duration.ofSeconds(2);
    static final Duration RETRY_CAP = Duration.ofMinutes(5);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;

    public OutboxPublisherService(OutboxRepository outbox, EventPublisher publisher, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Claims and delivers one batch.
     *
     * @param limit maximum records to take in this pass
     * @return how many records were acknowledged by the broker
     */
    public int publishPending(int limit) {
        Instant now = clock.instant();
        String claimToken = UUID.randomUUID().toString();
        List<OutboxRecord> batch = outbox.claimBatch(claimToken, limit, now, now.plus(CLAIM_LEASE));
        if (batch.isEmpty()) {
            return 0;
        }
        int acknowledged = 0;
        for (OutboxRecord record : batch) {
            if (send(record, claimToken)) {
                acknowledged++;
            }
        }
        return acknowledged;
    }

    private boolean send(OutboxRecord record, String claimToken) {
        try {
            publisher.publish(record.topic(), record.eventKey(), record.body());
        } catch (EventPublisher.PublishFailedException e) {
            log.warn("delivery of event {} failed on attempt {}: {}",
                    record.eventId(), record.publishAttempt() + 1, e.getMessage());
            outbox.reschedule(record.eventId(), claimToken, clock.instant().plus(backoffFor(record)));
            return false;
        }
        boolean marked = outbox.markPublished(record.eventId(), claimToken, clock.instant());
        if (!marked) {
            // Another publisher took the row over while this one was sending. The record was
            // delivered twice, which the consumer's inbox absorbs; it is not this claim's success.
            log.info("event {} was re-claimed during delivery; leaving the acknowledgement to the new claim",
                    record.eventId());
        }
        return marked;
    }

    private Duration backoffFor(OutboxRecord record) {
        int attempts = Math.max(1, record.publishAttempt() + 1);
        long millis = RETRY_BASE.toMillis() * (1L << Math.min(attempts - 1, 8));
        return Duration.ofMillis(Math.min(millis, RETRY_CAP.toMillis()));
    }

    /** Returns claims whose lease lapsed to the pending pool. */
    public int releaseExpiredClaims() {
        return outbox.releaseExpiredClaims(clock.instant());
    }
}
