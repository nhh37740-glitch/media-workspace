package com.mediaworkspace.worker.config;

import com.mediaworkspace.messaging.RequestEventConsumer;
import com.mediaworkspace.messaging.ResultEventConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the worker registers the consumer it depends on, and only that one.
 *
 * <p>{@code @KafkaListener} is applied by a bean post-processor that iterates the context's beans.
 * An annotated class that was never registered as a bean therefore produces no listener and no
 * error: the events are produced, the broker accepts them, the outbox row is marked delivered, and
 * the task sits in WAITING_EVENT until somebody notices. That is exactly what happened, and the only
 * thing that caught it was an end-to-end run.
 *
 * <p>The assertion is made by reflection over the configuration class rather than by starting a
 * context, so it runs in the unit-test phase with no database and no broker - which is the point,
 * because a wiring mistake must not be discoverable only when the whole system is up.
 */
class ListenerRegistrationTest {

    private static boolean declaresBeanOfType(Class<?> configuration, Class<?> beanType) {
        return Arrays.stream(configuration.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .anyMatch(method -> beanType.isAssignableFrom(method.getReturnType()));
    }

    @Test
    @DisplayName("the worker registers the request-event consumer")
    void workerRegistersRequestConsumer() {
        assertThat(declaresBeanOfType(WorkerConfiguration.class, RequestEventConsumer.class))
                .as("without this bean the worker reads no request event and every task stalls")
                .isTrue();
    }

    @Test
    @DisplayName("the worker does not register the result-event consumer")
    void workerDoesNotConsumeResults() {
        // Result events belong to the API's notification projection. A worker that consumed them
        // would advance that consumer group's offsets without producing the projection.
        assertThat(declaresBeanOfType(WorkerConfiguration.class, ResultEventConsumer.class)).isFalse();
    }
}
