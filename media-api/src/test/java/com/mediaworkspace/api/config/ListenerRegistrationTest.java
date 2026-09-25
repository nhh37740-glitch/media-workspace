package com.mediaworkspace.api.config;

import com.mediaworkspace.messaging.RequestEventConsumer;
import com.mediaworkspace.messaging.ResultEventConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the API registers exactly one of the two consumers.
 *
 * <p>See the worker's counterpart for why this is asserted by reflection over the configuration
 * class: an unregistered {@code @KafkaListener} is silent, and a wiring mistake must fail in the
 * unit-test phase rather than being discovered by an end-to-end run.
 */
class ListenerRegistrationTest {

    private static boolean declaresBeanOfType(Class<?> configuration, Class<?> beanType) {
        return Arrays.stream(configuration.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .anyMatch(method -> beanType.isAssignableFrom(method.getReturnType()));
    }

    @Test
    @DisplayName("the API registers the result-event consumer")
    void apiRegistersResultConsumer() {
        assertThat(declaresBeanOfType(ApiServiceConfiguration.class, ResultEventConsumer.class))
                .as("without this bean the audit and notification projection never runs")
                .isTrue();
    }

    @Test
    @DisplayName("the API does not consume request events")
    void apiDoesNotClaimWork() {
        // Consuming request events is what makes a process a worker. The API must not take that
        // role: it would advance the worker consumer group's offsets while claiming tasks this
        // process has no execution slots for.
        assertThat(declaresBeanOfType(ApiServiceConfiguration.class, RequestEventConsumer.class)).isFalse();
    }
}
