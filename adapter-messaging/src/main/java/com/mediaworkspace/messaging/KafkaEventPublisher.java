package com.mediaworkspace.messaging;

import com.mediaworkspace.application.port.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends outbox records to Kafka.
 *
 * <p>The producer is configured for idempotent sends with {@code acks=all}, so a retry inside the
 * client cannot duplicate a record within the producer session and an acknowledged send is on every
 * in-sync replica. That is a statement about the broker write, not about the business operation: the
 * business state was already committed before this class was called, and delivery remains
 * at-least-once, which is why the consumer side deduplicates.
 *
 * <p>A send is awaited. Returning before the broker acknowledged would let the publisher mark the
 * outbox row as delivered for a record that was never written.
 *
 * <p>The environment prefix is applied <b>here</b>, at the moment of sending, and not when the
 * outbox row is written. The row therefore stores the logical topic name, which stays valid if the
 * prefix changes - and, more importantly, this is the single place where publishing resolves the
 * name. Resolving it in the producer and in the consumer by two different routes is exactly how the
 * producer came to write to {@code media.task.requested.v1} while the worker listened on
 * {@code mwmedia.task.requested.v1}: the event was accepted by the broker and never consumed.
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> template;
    private final TopicNames topics;
    private final Duration sendTimeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> template, TopicNames topics,
                               Duration sendTimeout) {
        this.template = template;
        this.topics = topics;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(String logicalTopic, String key, String payload) throws PublishFailedException {
        String topic = topics.resolve(logicalTopic);
        try {
            SendResult<String, String> result = template.send(topic, key, payload)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            // Guarded because a diagnostic must never be able to fail a delivery. A client that
            // answers with a bare future is unusual, but a log line dereferencing it would turn
            // "message sent" into an exception.
            if (result != null) {
                log.debug("event acknowledged: topic={} partition={} offset={}",
                        topic, result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.debug("event acknowledged: topic={} (the client did not report a partition)", topic);
            }
        } catch (TimeoutException e) {
            throw new PublishFailedException("the broker did not acknowledge within " + sendTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("the send was interrupted", e);
        } catch (ExecutionException e) {
            throw new PublishFailedException("the broker rejected the record: " + e.getCause().getMessage(),
                    e.getCause());
        }
    }
}
