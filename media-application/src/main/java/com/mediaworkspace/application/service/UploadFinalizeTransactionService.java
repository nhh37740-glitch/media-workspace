package com.mediaworkspace.application.service;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.model.CapacitySnapshot;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.OutboxRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.model.Workspace;
import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.port.repository.CapacityRepository;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventTopics;
import com.mediaworkspace.contracts.event.EventType;
import com.mediaworkspace.contracts.event.TaskRequestedPayload;
import com.mediaworkspace.contracts.model.MediaState;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import com.mediaworkspace.contracts.model.TranscodePreset;
import com.mediaworkspace.contracts.model.UploadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional steps of the finalize flow.
 *
 * <p>Separate from {@link UploadFinalizeService} because that class performs file I/O which must
 * not run inside a database transaction. Each method here is one short transaction with an
 * explicit lock order: capacity counter, then workspace, then upload session, then media, then
 * task. Where an identifier is needed before the lock is taken, a non-locking read supplies it and
 * the association is re-checked under the lock.
 */
public class UploadFinalizeTransactionService {

    private static final Logger log = LoggerFactory.getLogger(UploadFinalizeTransactionService.class);

    private final UploadRepository uploads;
    private final MediaRepository media;
    private final TaskRepository tasks;
    private final CapacityRepository capacity;
    private final WorkspaceRepository workspaces;
    private final OutboxRepository outbox;
    private final EventSerializer eventSerializer;
    private final MediaWorkspaceProperties properties;
    private final Clock clock;

    public UploadFinalizeTransactionService(UploadRepository uploads, MediaRepository media,
                                            TaskRepository tasks, CapacityRepository capacity,
                                            WorkspaceRepository workspaces, OutboxRepository outbox,
                                            EventSerializer eventSerializer,
                                            MediaWorkspaceProperties properties, Clock clock) {
        this.uploads = uploads;
        this.media = media;
        this.tasks = tasks;
        this.capacity = capacity;
        this.workspaces = workspaces;
        this.outbox = outbox;
        this.eventSerializer = eventSerializer;
        this.properties = properties;
        this.clock = clock;
    }

    /** A merge lease held by this finalizer. */
    public record FinalizeLease(UploadSession session, long epoch) {
    }

    /** The next session eligible for merging, if any. */
    @Transactional(readOnly = true)
    public Optional<String> pickCandidate() {
        return uploads.findFinalizeCandidates(1).stream()
                .map(UploadSession::id)
                .findFirst();
    }

    /** Sessions whose merge lease lapsed and which another finalizer may take over. */
    @Transactional(readOnly = true)
    public List<UploadSession> staleFinalizing(int limit) {
        return uploads.findStaleFinalizing(limit);
    }

    /**
     * Takes the merge lease for a session.
     *
     * <p>The epoch increments on every acquisition, so a finalizer whose lease lapsed silently
     * loses the right to publish even though it may still be writing its own file.
     */
    @Transactional
    public Optional<FinalizeLease> acquireLease(String uploadId) {
        UploadSession locked = uploads.lockById(uploadId).orElse(null);
        if (locked == null || locked.state() != UploadState.FINALIZING) {
            return Optional.empty();
        }
        return uploads.acquireFinalizeLease(uploadId, properties.uploadLeaseDuration())
                .map(epoch -> new FinalizeLease(locked, epoch));
    }

    /** Releases a lapsed lease without changing the session state. */
    @Transactional
    public boolean releaseLease(String uploadId, long epoch, Duration nextAttemptDelay) {
        return uploads.releaseFinalizeLease(uploadId, epoch, nextAttemptDelay);
    }

    /** Expires OPEN sessions past their deadline. */
    @Transactional
    public int expireOpenSessions(int limit) {
        int expired = 0;
        for (UploadSession session : uploads.findExpiredOpen(limit)) {
            if (uploads.markTerminated(session.id(), UploadState.EXPIRED, session.quotaReserved())) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Publishes the merged original as a media row plus a task, or defers when there is no room.
     *
     * <p>The capacity counter is locked first, so the admission decision and the task insert are
     * one atomic step. When the counter is full nothing is written except a later retry deadline:
     * the reservation stays, the merged file stays, and the capacity counter is not incremented.
     */
    @Transactional
    public UploadFinalizeService.Outcome publish(FinalizeLease lease, String sourceKey, long sizeBytes,
                                                 String requestId) {
        Instant now = clock.instant();
        Optional<CapacitySnapshot> counter = capacity.lock(CapacityRepository.PROCESSING);
        if (counter.isEmpty()) {
            log.error("capacity counter row is missing; refusing to publish upload {}", lease.session().id());
            return UploadFinalizeService.Outcome.FAILED;
        }
        if (!counter.get().hasFreeSlot()) {
            // Bounded waiting, not a failure: the merged file and the reservation are kept, and the
            // session stays FINALIZING so a later pass can publish it. The task counter is not
            // touched, because no task was created.
            uploads.releaseFinalizeLease(lease.session().id(), lease.epoch(),
                    properties.uploadLeaseDuration());
            return UploadFinalizeService.Outcome.CAPACITY_WAIT;
        }

        String workspaceId = lease.session().workspaceId();
        Workspace workspace = workspaces.lockForReservation(workspaceId).orElse(null);
        if (workspace == null) {
            return UploadFinalizeService.Outcome.SUPERSEDED;
        }
        UploadSession locked = uploads.lockById(lease.session().id()).orElse(null);
        if (!isStillCurrent(locked, lease, now)) {
            return UploadFinalizeService.Outcome.SUPERSEDED;
        }
        if (locked.mediaId() != null) {
            // A previous finalizer already published this session.
            return UploadFinalizeService.Outcome.PUBLISHED;
        }

        String mediaId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String preset = TranscodePreset.MP4_720P_V1.name();
        media.insert(new MediaRecord(
                mediaId, workspaceId, locked.ownerId(), locked.title(), locked.filename(),
                sourceKey, sizeBytes, locked.expectedHash(), MediaState.PROCESSING, 0L,
                null, null, null, null, null, null, now));
        tasks.insert(taskId, mediaId, locked.traceId(), preset, requestId);
        capacity.increment(CapacityRepository.PROCESSING);
        workspaces.moveReservedToUsed(workspaceId, sizeBytes);
        uploads.markCompleted(locked.id(), mediaId, lease.epoch());
        appendRequestedEvent(locked, mediaId, taskId, preset, requestId);
        log.info("published upload {} as media {} with task {}", locked.id(), mediaId, taskId);
        return UploadFinalizeService.Outcome.PUBLISHED;
    }

    private boolean isStillCurrent(UploadSession locked, FinalizeLease lease, Instant now) {
        return locked != null
                && locked.state() == UploadState.FINALIZING
                && locked.finalizeEpoch() == lease.epoch()
                && locked.leaseUntil() != null
                && locked.leaseUntil().isAfter(now);
    }

    private void appendRequestedEvent(UploadSession session, String mediaId, String taskId,
                                      String preset, String requestId) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(), EventType.TASK_REQUESTED, EventEnvelope.SCHEMA_VERSION,
                clock.instant(), mediaId, taskId, 1, 1L, requestId, session.traceId(),
                UUID.randomUUID().toString(), null, new TaskRequestedPayload(TranscodePreset.valueOf(preset)));
        outbox.append(new OutboxRecord(
                envelope.eventId(), EventTopics.TASK_REQUESTED, mediaId,
                eventSerializer.toJson(envelope), "PENDING", 0, clock.instant(), null, null, null));
    }

    /**
     * Fails a session whose merge produced a definite, non-recoverable outcome.
     *
     * <p>The reservation is released exactly once. A session whose outcome is merely unknown is
     * not handled here: it is left FINALIZING for lease recovery, because releasing a reservation
     * that a still-running finalizer might yet need would let the quota drift.
     */
    @Transactional
    public UploadFinalizeService.Outcome failMerge(FinalizeLease lease, TaskErrorCode errorCode,
                                                   String summary) {
        Instant now = clock.instant();
        UploadSession locked = uploads.lockById(lease.session().id()).orElse(null);
        if (!isStillCurrent(locked, lease, now)) {
            return UploadFinalizeService.Outcome.SUPERSEDED;
        }
        uploads.markFailed(locked.id(), errorCode.name(), locked.quotaReserved());
        log.info("finalize failed for upload {}: {} ({})", locked.id(), errorCode, summary);
        return UploadFinalizeService.Outcome.FAILED;
    }
}
