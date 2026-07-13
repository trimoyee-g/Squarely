package com.squarely.notification.kafka;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Retry-then-dead-letter for the consumers. Same shape as ledger-service's: a record this service
 * cannot process goes to {@code <topic>.DLT} instead of being retried, logged, and dropped.
 *
 * <p>A missed notification is cheaper than a missed ledger entry, but the failure mode that makes
 * the handler necessary is the same: without it a poison message wedges the partition, and then
 * *every* later notification is missed too.
 */
@Configuration
public class KafkaErrorConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaProperties properties) {
        // Failed-deserialization payloads arrive as raw byte[], real events as objects.
        Serializer<Object> serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                Object.class, new JsonSerializer<>()));

        var producerFactory = new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null), serializer, serializer);
        var recoverer = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producerFactory));

        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    }
}
