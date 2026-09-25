package com.mediaworkspace.messaging;

import com.mediaworkspace.contracts.event.EventTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prefix exists so one broker can host the demonstration topics and several isolated test runs.
 *
 * <p>The failure these assertions guard against is subtle: the producer and the consumer resolve the
 * name through different code paths, so if only one of them applies the prefix the record is written
 * to a topic nobody reads. The broker accepts it, the outbox row is marked delivered, and the task
 * simply never starts - a silent stall rather than an error.
 */
class TopicNamesTest {

    @Test
    @DisplayName("an empty prefix leaves the contract topic names untouched")
    void withoutPrefix() {
        TopicNames topics = new TopicNames("");
        assertThat(topics.requested()).isEqualTo("media.task.requested.v1");
        assertThat(topics.result()).isEqualTo("media.task.result.v1");
        assertThat(topics.deadLetter()).isEqualTo("media.events.dlq.v1");
        assertThat(topics.group(EventTopics.WORKER_GROUP)).isEqualTo("media-worker-v1");
    }

    @Test
    @DisplayName("a prefix is applied to topics and consumer groups alike")
    void withPrefix() {
        TopicNames topics = new TopicNames("mw-it-abc123-");
        assertThat(topics.requested()).isEqualTo("mw-it-abc123-media.task.requested.v1");
        assertThat(topics.result()).isEqualTo("mw-it-abc123-media.task.result.v1");
        assertThat(topics.group(EventTopics.WORKER_GROUP)).isEqualTo("mw-it-abc123-media-worker-v1");
    }

    @Test
    @DisplayName("a blank prefix is treated as absent rather than producing a leading separator")
    void blankPrefixIsAbsent() {
        assertThat(new TopicNames("   ").requested()).isEqualTo("media.task.requested.v1");
        assertThat(new TopicNames(null).requested()).isEqualTo("media.task.requested.v1");
    }

    @Test
    @DisplayName("resolving an already resolved name does not apply the prefix twice")
    void resolveIsIdempotentForLogicalNames() {
        // The publisher resolves once, at the moment of sending. Every other caller passes the
        // logical name, so a double application would be a programming error rather than something
        // the class silently tolerates - which is why resolve() is called exactly once per send.
        TopicNames topics = new TopicNames("mw-");
        assertThat(topics.resolve(EventTopics.TASK_REQUESTED))
                .isEqualTo(topics.requested())
                .isEqualTo("mw-media.task.requested.v1");
    }
}
