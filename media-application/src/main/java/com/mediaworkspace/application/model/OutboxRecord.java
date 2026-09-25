package com.mediaworkspace.application.model;

import java.time.Instant;

/**
 * A pending or already-published outbox row.
 *
 * @param claimToken token of the current claim; a publisher may only mark the row published if
 *                   the token still matches, so a re-claimed row is not acknowledged by the
 *                   previous claimant
 */
public record OutboxRecord(
        String eventId,
        String topic,
        String eventKey,
        String body,
        String state,
        int publishAttempt,
        Instant nextRunAt,
        String claimToken,
        Instant claimUntil,
        Instant publishedAt) {
}
