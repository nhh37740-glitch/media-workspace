package com.mediaworkspace.messaging;

import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.service.DeadLetterService;
import com.mediaworkspace.application.service.TaskIntakeService;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes request events and hands them to the intake use case.
 *
 * <p>The offset is acknowledged by hand and only after the intake transaction has committed. Auto
 * commit is off, so the commit point is exactly "the inbox row and the task transition are durable".
 * A crash between the commit and the acknowledgement therefore replays the record, and the inbox
 * makes the replay a no-op rather than a second transition.
 *
 * <p>Acknowledgements are issued per record in the order the records were consumed, so a larger
 * offset is never committed while a smaller one is still unprocessed.
 */
public class RequestEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RequestEventConsumer.class);

    private final TaskIntakeService intake;
    private final DeadLetterService deadLetters;
    private final EventSerializer serializer;
    private final String consumerGroup;

    public RequestEventConsumer(TaskIntakeService intake, DeadLetterService deadLetters,
                                EventSerializer serializer, TopicNames topics) {
        this.intake = intake;
        this.deadLetters = deadLetters;
        this.serializer = serializer;
        this.consumerGroup = topics.group(EventTopics.WORKER_GROUP);
    }

    @KafkaListener(
            topics = "#{@topicNames.requested()}",
            groupId = "#{@topicNames.group('media-worker-v1')}",
            containerFactory = "orderedRecordListenerContainerFactory")
    public void onRequested(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String bodyHash = IdempotencyKeys.sha256Hex(record.value());
        EventEnvelope envelope;
        try {
            envelope = serializer.fromJson(record.value());
        } catch (EventSerializer.MalformedEventException e) {
            isolate(record, bodyHash, "MALFORMED_EVENT", e.getMessage());
            acknowledgment.acknowledge();
            return;
        }
        if (!envelope.isKnownSchemaVersion()) {
            // An unknown major version is not retried: the running build cannot interpret it, and
            // retrying would stall the partition until an upgrade that may not be coming.
            isolate(record, bodyHash, "UNSUPPORTED_SCHEMA_VERSION",
                    "schemaVersion=" + envelope.schemaVersion());
            acknowledgment.acknowledge();
            return;
        }
        try {
            TaskIntakeService.IntakeOutcome outcome = intake.apply(envelope, bodyHash, consumerGroup);
            log.debug("request event {} -> {}", envelope.eventId(), outcome);
        } catch (RuntimeException e) {
            // The transaction rolled back, so the inbox row is not durable either. Propagating
            // leaves the offset uncommitted and the record is redelivered.
            log.warn("intake of event {} failed and will be redelivered: {}",
                    envelope.eventId(), e.getMessage());
            throw e;
        }
        acknowledgment.acknowledge();
    }

    /**
     * Moves an unreadable record to the dead-letter path.
     *
     * <p>Called before the offset is acknowledged: the isolation transaction writes the poison row
     * and the dead-letter outbox record together, so a broker outage afterwards delays the dead
     * letter instead of losing it.
     */
    private void isolate(ConsumerRecord<String, String> record, String bodyHash, String errorCode,
                         String detail) {
        log.warn("isolating an unreadable record at {}-{}@{}: {} ({})",
                record.topic(), record.partition(), record.offset(), errorCode, detail);
        deadLetters.isolate(record.topic(), record.partition(), record.offset(), errorCode,
                bodyHash, record.value());
    }
}
