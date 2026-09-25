package com.mediaworkspace.messaging;

import com.mediaworkspace.application.port.messaging.EventPublisher;
import com.mediaworkspace.application.port.messaging.EventSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer, consumer and listener wiring.
 *
 * <p>Settings that carry contract meaning rather than tuning:
 * <ul>
 *   <li>{@code enable.idempotence=true} with {@code acks=all}: a client-side retry cannot duplicate a
 *       record within the producer session, and an acknowledgement means every in-sync replica has
 *       it. This is a statement about the broker write, not about the business operation.</li>
 *   <li>{@code enable.auto.commit=false} with manual acknowledgement: the offset is committed only
 *       after the consumer's transaction committed, so the commit point is "the work is durable".</li>
 *   <li>{@code auto.offset.reset=earliest}: events published while a consumer was down are processed
 *       when it returns, which is what makes a broker outage a delay rather than a loss.</li>
 *   <li>{@code max.poll.records} kept small so the transaction after a poll is short and a
 *       redelivery after a crash re-does little work.</li>
 * </ul>
 */
@Configuration
@EnableKafka
public class MessagingConfiguration {

    @Bean
    public TopicNames topicNames(@Value("${mediaworkspace.kafka.topic-prefix:}") String prefix) {
        return new TopicNames(prefix);
    }

    @Bean
    public EventSerializer eventSerializer() {
        return new JacksonEventSerializer();
    }

    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 60_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 20_000);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public EventPublisher eventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                         @Value("${mediaworkspace.kafka.send-timeout-ms:15000}") long sendTimeoutMs) {
        return new KafkaEventPublisher(kafkaTemplate, Duration.ofMillis(sendTimeoutMs));
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    /**
     * Container factory for the ordered, hand-acknowledged consumers.
     *
     * <p>{@code AckMode.MANUAL} keeps acknowledgement ordering: an acknowledgement is queued behind
     * the earlier ones for the same partition, so a larger offset can never be committed while a
     * smaller one is still unprocessed. Concurrency stays at one per partition by default, which is
     * the smallest number of moving parts that can still keep per-partition order.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> orderedRecordListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${mediaworkspace.kafka.listener-concurrency:1}") int concurrency,
            @Value("${mediaworkspace.kafka.listener-auto-startup:true}") boolean autoStartup) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.setBatchListener(false);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setMissingTopicsFatal(false);
        factory.getContainerProperties().setShutdownTimeout(20_000L);
        factory.getContainerProperties().setPollTimeout(1_000L);
        // This factory is constructed here rather than by Boot, so Boot's own
        // spring.kafka.listener.auto-startup property does not reach it. The setting is therefore
        // exposed under this project's own prefix, which is what lets the bootstrap command run
        // without consumer threads - those threads are not daemons and would keep an otherwise
        // finished process alive forever. Auto-startup belongs to the factory, not to the container
        // properties, because it decides whether a container is started at all.
        factory.setAutoStartup(autoStartup);
        return factory;
    }
}
