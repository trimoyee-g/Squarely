package com.squarely.ledger.kafka;

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
 * Retry-then-dead-letter for the consumers. Boot picks this {@link DefaultErrorHandler} up as the
 * container's error handler automatically.
 *
 * <p>Without it, a record this service cannot process (bad payload, or a DB error that outlives
 * the retries) is retried, logged, and its offset committed — the expense is gone, silently, and
 * the balance is wrong forever. With it, the record lands on {@code <topic>.DLT} where it can be
 * inspected and replayed.
 *
 * <p>ponytail: nothing consumes the DLT. It is a durable parking lot, not a workflow — replay is
 * a console-consumer away. Build automatic replay when someone is actually on the hook to do it.
 */
@Configuration
public class KafkaErrorConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaProperties properties) {
        // Payloads that failed deserialization arrive here as raw byte[]; everything else is a
        // real event object. One serializer that handles both, so the DLT record keeps the
        // original bytes instead of throwing a second time on the way out.
        Serializer<Object> serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                Object.class, new JsonSerializer<>()));

        var producerFactory = new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(null), serializer, serializer);
        var recoverer = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producerFactory));

        // 3 retries, 1s apart. A poison message fails all four attempts in ~3s rather than
        // blocking the partition; a transient DB blip usually survives the first retry.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    }
}
