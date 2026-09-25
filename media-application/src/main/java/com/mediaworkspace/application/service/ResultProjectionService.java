package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.port.repository.AuditRepository;
import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Builds the API-side notification and audit projections from result events.
 *
 * <p>This consumer is deliberately not authoritative. The task row is already final by the time a
 * result event exists, because the worker wrote it in the same transaction that appended the
 * event. So a duplicate, a delay or a reordering here cannot change or roll back the task state:
 * the projection only appends deduplicated audit records, and the SSE stream separately reads the
 * authoritative row. That is what allows a terminal state to remain queryable even while Kafka is
 * down.
 */
public class ResultProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ResultProjectionService.class);

    private final InboxRepository inbox;
    private final AuditRepository audit;
    private final TaskRepository tasks;
    private final MediaRepository media;
    private final Clock clock;

    public ResultProjectionService(InboxRepository inbox, AuditRepository audit, TaskRepository tasks,
                                   MediaRepository media, Clock clock) {
        this.inbox = inbox;
        this.audit = audit;
        this.tasks = tasks;
        this.media = media;
        this.clock = clock;
    }

    /** Whether the projection changed anything, for logging. */
    public enum ProjectionOutcome {
        PROJECTED,
        DUPLICATE,
        IGNORED
    }

    /**
     * Applies one result event.
     *
     * <p>The inbox row and the audit row are written in one transaction, so a redelivery finds the
     * inbox row and appends nothing further. The audit repository additionally enforces uniqueness
     * on the source event id, which keeps the projection single even if the inbox is bypassed.
     */
    @Transactional
    public ProjectionOutcome apply(EventEnvelope envelope, String bodyHash, String consumerGroup) {
        InboxRepository.IntakeResult intake = inbox.intake(
                consumerGroup, envelope.eventId(), bodyHash, clock.instant());
        if (intake == InboxRepository.IntakeResult.DUPLICATE) {
            return ProjectionOutcome.DUPLICATE;
        }
        if (intake == InboxRepository.IntakeResult.CONFLICT) {
            log.warn("result event {} was already processed with a different body; isolating it",
                    envelope.eventId());
            return ProjectionOutcome.IGNORED;
        }
        Optional<ProcessingTaskRecord> task = tasks.findById(envelope.taskId());
        if (task.isEmpty()) {
            return ProjectionOutcome.IGNORED;
        }
        String workspaceId = media.findAnyIncludingDeleted(task.get().mediaId())
                .map(record -> record.workspaceId())
                .orElse(null);
        if (workspaceId == null) {
            return ProjectionOutcome.IGNORED;
        }
        audit.appendFromEvent(workspaceId, null, actionOf(envelope.eventType()),
                envelope.mediaId(), envelope.requestId(), detailOf(envelope), envelope.eventId());
        return ProjectionOutcome.PROJECTED;
    }

    private static String actionOf(EventType type) {
        return switch (type) {
            case TASK_REQUESTED -> "TASK_REQUESTED";
            case TASK_SUCCEEDED -> "TASK_SUCCEEDED";
            case TASK_FAILED -> "TASK_FAILED";
            case TASK_CANCELLED -> "TASK_CANCELLED";
        };
    }

    /**
     * A small, safe detail document.
     *
     * <p>Only identifiers, counts and codes: no paths, no storage keys, no payload echoes that
     * could carry something the uploader did not intend to publish.
     */
    private static String detailOf(EventEnvelope envelope) {
        return "{\"eventType\":\"" + envelope.eventType().name() + "\",\"generation\":"
                + envelope.generation() + ",\"schemaVersion\":" + envelope.schemaVersion()
                + ",\"traceId\":\"" + envelope.traceId() + "\"}";
    }
}
