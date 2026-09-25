package com.mediaworkspace.messaging;

import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.service.DeadLetterService;
import com.mediaworkspace.application.service.ResultProjectionService;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.event.EventEnvelope;
import com.mediaworkspace.contracts.event.EventTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes task result events in the API and builds the notification and audit projection.
 *
 * <p>This consumer is deliberately not authoritative. The task row was already made final by the
 * worker in the transaction that appended the event, so a duplicate, a delay or a reordering here
 * cannot change or roll back task state. The projection only appends deduplicated audit records, and
 * the SSE stream reads the authoritative row separately. That separation is why a terminal state
 * stays queryable while Kafka is unavailable.
 *
 * <p>A record this consumer cannot interpret is isolated rather than retried, and the offset is
 * acknowledged only after either the projection transaction or the isolation transaction committed.
 */
public class ResultEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResultEventConsumer.class);

    private final ResultProjectionService projection;
    private final DeadLetterService deadLetters;
    private final EventSerializer serializer;
    private final String consumerGroup;

    public ResultEventConsumer(ResultProjectionService projection, DeadLetterService deadLetters,
                               EventSerializer serializer, TopicNames topics) {
        this.projection = projection;
        this.deadLetters = deadLetters;
        this.serializer = serializer;
        this.consumerGroup = topics.group(EventTopics.API_NOTIFY_GROUP);
    }

    @KafkaListener(
            topics = "#{@topicNames.result()}",
            groupId = "#{@topicNames.group('media-api-notify-v1')}",
            containerFactory = "orderedRecordListenerContainerFactory")
    public void onResult(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
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
            isolate(record, bodyHash, "UNSUPPORTED_SCHEMA_VERSION",
                    "schemaVersion=" + envelope.schemaVersion());
            acknowledgment.acknowledge();
            return;
        }
        try {
            ResultProjectionService.ProjectionOutcome outcome =
                    projection.apply(envelope, bodyHash, consumerGroup);
            log.debug("result event {} -> {}", envelope.eventId(), outcome);
        } catch (RuntimeException e) {
            // The projection transaction rolled back; leaving the offset uncommitted redelivers it.
            log.warn("projection of event {} failed and will be redelivered: {}",
                    envelope.eventId(), e.getMessage());
            throw e;
        }
        acknowledgment.acknowledge();
    }

    private void isolate(ConsumerRecord<String, String> record, String bodyHash, String errorCode,
                         String detail) {
        log.warn("isolating an unreadable result record at {}-{}@{}: {} ({})",
                record.topic(), record.partition(), record.offset(), errorCode, detail);
        deadLetters.isolate(record.topic(), record.partition(), record.offset(), errorCode,
                bodyHash, record.value());
    }
}
