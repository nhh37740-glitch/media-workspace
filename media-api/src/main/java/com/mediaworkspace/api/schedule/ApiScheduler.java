package com.mediaworkspace.api.schedule;

import com.mediaworkspace.application.service.OutboxPublisherService;
import com.mediaworkspace.application.service.UploadFinalizeService;
import com.mediaworkspace.application.port.repository.ShareSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Background work owned by the API process.
 *
 * <p>Each pass is short and independent. Nothing is queued in memory: the state a pass needs is read
 * from the database at the start of the pass, which is why an API restart neither loses pending
 * merges nor leaves them claimed. A pass that finds nothing does one query and returns.
 */
@Component
@ConditionalOnProperty(name = "mediaworkspace.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ApiScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApiScheduler.class);

    /** How many sessions one finalize pass may consider; one merge at a time on a two-core host. */
    private static final int FINALIZE_BATCH = 1;
    private static final int MAINTENANCE_BATCH = 50;

    private final UploadFinalizeService finalizeService;
    private final OutboxPublisherService outboxPublisher;
    private final ShareSessionRepository shareSessions;
    private final Clock clock;

    private final int outboxBatchSize;

    public ApiScheduler(UploadFinalizeService finalizeService,
                        OutboxPublisherService outboxPublisher, ShareSessionRepository shareSessions,
                        Clock clock,
                        @org.springframework.beans.factory.annotation.Value(
                                "${mediaworkspace.scheduler.outbox-batch-size:25}") int outboxBatchSize) {
        this.finalizeService = finalizeService;
        this.outboxPublisher = outboxPublisher;
        this.shareSessions = shareSessions;
        this.clock = clock;
        this.outboxBatchSize = outboxBatchSize;
    }

    /**
     * Merges at most one finalized upload and publishes it.
     *
     * <p>One at a time on purpose. The merge reads and writes a whole original file, and running
     * several on a two-core host would starve the HTTP requests the same process serves.
     */
    @Scheduled(fixedDelayString = "${mediaworkspace.scheduler.finalize-interval-ms:1000}")
    public void finalizeUploads() {
        for (int i = 0; i < FINALIZE_BATCH; i++) {
            UploadFinalizeService.Outcome outcome = finalizeService.finalizeNext(newRequestId());
            if (outcome == UploadFinalizeService.Outcome.NONE) {
                return;
            }
            log.debug("finalize pass outcome: {}", outcome);
        }
    }

    /**
     * Delivers pending outbox records.
     *
     * <p>Runs in the API as well as in the worker: whichever process is up can drain the outbox, so
     * a stopped worker does not stop result notifications, and a broker outage delays both without
     * losing either.
     */
    @Scheduled(fixedDelayString = "${mediaworkspace.scheduler.outbox-interval-ms:1000}")
    public void publishOutbox() {
        outboxPublisher.releaseExpiredClaims();
        int published = outboxPublisher.publishPending(outboxBatchSize);
        if (published > 0) {
            log.debug("published {} outbox record(s)", published);
        }
    }

    /** Expires abandoned uploads, recovers lapsed merge leases and drops expired share sessions. */
    @Scheduled(fixedDelayString = "${mediaworkspace.scheduler.maintenance-interval-ms:15000}")
    public void maintain() {
        int expired = finalizeService.expireOpenSessions(MAINTENANCE_BATCH);
        if (expired > 0) {
            log.info("expired {} abandoned upload session(s)", expired);
        }
        int recovered = finalizeService.recoverStaleLeases(MAINTENANCE_BATCH);
        if (recovered > 0) {
            log.info("released {} lapsed merge lease(s)", recovered);
        }
        int sessions = shareSessions.deleteExpired(clock.instant(), MAINTENANCE_BATCH);
        if (sessions > 0) {
            log.debug("removed {} expired share session(s)", sessions);
        }
    }

    /**
     * A maintenance pass has no HTTP request behind it.
     *
     * <p>It still needs a request id, because the contract requires every record to carry one and a
     * recovery action must not be attributed to a request that is not running. A fresh id is
     * generated rather than reusing the trace id, which would claim the original request is still
     * executing.
     */
    private String newRequestId() {
        return java.util.UUID.randomUUID().toString();
    }
}
