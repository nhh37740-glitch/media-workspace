package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.contracts.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Consumes request events and moves the matching task from WAITING_EVENT to QUEUED.
 *
 * <p>The inbox record and the state change share one transaction, and the consumer commits its
 * offset only after that transaction commits. A redelivery therefore finds the inbox row and
 * performs no second transition, while a crash before the commit leaves both undone so the
 * redelivery does the work once.
 *
 * <p>The task row is never created here. A task always originates from the upload finalize
 * transaction, which is why there is only one creator and no race between "Kafka creates the task"
 * and "the database creates the task".
 */
public class TaskIntakeService {

    private static final Logger log = LoggerFactory.getLogger(TaskIntakeService.class);

    private final InboxRepository inbox;
    private final TaskRepository tasks;
    private final Clock clock;

    public TaskIntakeService(InboxRepository inbox, TaskRepository tasks, Clock clock) {
        this.inbox = inbox;
        this.tasks = tasks;
        this.clock = clock;
    }

    /** What happened to one consumed record. */
    public enum IntakeOutcome {
        /** The task was moved to QUEUED. */
        QUEUED,
        /** Already processed with an identical body. */
        DUPLICATE,
        /** The task was cancelled or its generation moved on; the record is recorded and ignored. */
        IGNORED
    }

    /**
     * Applies one request event.
     *
     * @param consumerGroup consumer group the record was delivered to
     * @param bodyHash      hash of the raw record body, used to detect a same-id-different-body
     *                      conflict rather than treating it as a duplicate
     * @throws com.mediaworkspace.application.error.ApplicationException never; a conflicting body
     *         is reported through the return value so the caller can route it to the dead-letter
     *         path within its own transaction
     */
    @Transactional
    public IntakeOutcome apply(EventEnvelope envelope, String bodyHash, String consumerGroup) {
        InboxRepository.IntakeResult intake = inbox.intake(
                consumerGroup, envelope.eventId(), bodyHash, clock.instant());
        switch (intake) {
            case DUPLICATE:
                return IntakeOutcome.DUPLICATE;
            case CONFLICT:
                log.warn("event {} was already processed with a different body; isolating it",
                        envelope.eventId());
                return IntakeOutcome.IGNORED;
            case NEW:
            default:
                return applyToTask(envelope);
        }
    }

    private IntakeOutcome applyToTask(EventEnvelope envelope) {
        Optional<ProcessingTaskRecord> task = tasks.findById(envelope.taskId());
        if (task.isEmpty()) {
            // The task was deleted with its media. Nothing to schedule; the record is still
            // recorded in the inbox so a redelivery stays a no-op.
            return IntakeOutcome.IGNORED;
        }
        ProcessingTaskRecord record = task.get();
        if (record.generation() != envelope.generation()) {
            // An older generation's request: the task has already been retried past it.
            return IntakeOutcome.IGNORED;
        }
        // markQueued only matches a row still in WAITING_EVENT, so anything else (already queued,
        // running, cancelled) leaves zero rows affected and is recorded as an ignore.
        return tasks.markQueued(record.id(), record.generation())
                ? IntakeOutcome.QUEUED
                : IntakeOutcome.IGNORED;
    }
}
