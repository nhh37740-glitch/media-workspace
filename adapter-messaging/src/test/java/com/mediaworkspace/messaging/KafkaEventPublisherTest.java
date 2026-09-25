package com.mediaworkspace.messaging;

import com.mediaworkspace.application.port.messaging.EventPublisher;
import com.mediaworkspace.contracts.event.EventTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The publisher is where the environment prefix is applied, so this is where the producer side of
 * the name resolution is asserted. The regression it guards: the producer wrote the logical name
 * while the consumer listened on the prefixed one, so events were accepted by the broker and never
 * read, and a task sat in WAITING_EVENT for as long as it was given.
 */
class KafkaEventPublisherTest {

    /** Records what was sent, and answers with a completed send so the call returns. */
    private static final class RecordingTemplate extends KafkaTemplate<String, String> {
        private final List<String> topics = new ArrayList<>();
        private final RuntimeException failure;

        RecordingTemplate(RuntimeException failure) {
            super(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(java.util.Map.of(
                    "bootstrap.servers", "127.0.0.1:1",
                    "key.serializer", org.apache.kafka.common.serialization.StringSerializer.class,
                    "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class)));
            this.failure = failure;
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            topics.add(topic);
            if (failure != null) {
                CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
                failed.completeExceptionally(failure);
                return failed;
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    @DisplayName("an unprefixed environment sends to the contract topic name")
    void sendsToLogicalNameWhenNoPrefixIsConfigured() {
        RecordingTemplate template = new RecordingTemplate(null);
        EventPublisher publisher = new KafkaEventPublisher(template, new TopicNames(""), Duration.ofSeconds(5));

        publisher.publish(EventTopics.TASK_REQUESTED, "media-1", "{}");

        assertThat(template.topics).containsExactly("media.task.requested.v1");
    }

    @Test
    @DisplayName("a prefixed environment sends to the same topic the consumer listens on")
    void sendsToThePrefixedName() {
        RecordingTemplate template = new RecordingTemplate(null);
        TopicNames topics = new TopicNames("mw-it-run42-");
        EventPublisher publisher = new KafkaEventPublisher(template, topics, Duration.ofSeconds(5));

        publisher.publish(EventTopics.TASK_REQUESTED, "media-1", "{}");

        assertThat(template.topics).containsExactly(topics.requested());
        // Stated separately so the intent survives a change to the resolver: the name the consumer
        // builds and the name the producer sent must be the same string.
        assertThat(template.topics.get(0)).isEqualTo("mw-it-run42-media.task.requested.v1");
    }

    @Test
    @DisplayName("the dead-letter topic is prefixed the same way")
    void prefixesTheDeadLetterTopic() {
        RecordingTemplate template = new RecordingTemplate(null);
        EventPublisher publisher =
                new KafkaEventPublisher(template, new TopicNames("mw-"), Duration.ofSeconds(5));

        publisher.publish(EventTopics.EVENTS_DLQ, "media-1", "{}");

        assertThat(template.topics).containsExactly("mw-media.events.dlq.v1");
    }

    @Test
    @DisplayName("a rejected send raises so the outbox row is rescheduled rather than acknowledged")
    void reportsRejection() {
        RecordingTemplate template = new RecordingTemplate(
                new org.apache.kafka.common.errors.TimeoutException("not acknowledged"));
        EventPublisher publisher = new KafkaEventPublisher(template, new TopicNames(""), Duration.ofSeconds(5));

        // The publisher must not swallow this: a swallowed failure would let the caller mark the row
        // published and the event would be lost.
        assertThatThrownBy(() -> publisher.publish(EventTopics.TASK_REQUESTED, "media-1", "{}"))
                .isInstanceOf(EventPublisher.PublishFailedException.class);
    }
}
