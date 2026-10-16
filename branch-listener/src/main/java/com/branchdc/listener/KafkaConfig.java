package com.branchdc.listener;

import com.branchdc.events.BranchSyncEvent;
import com.branchdc.listener.BranchClient.BranchRejectedException;
import com.branchdc.listener.BranchEventListener.EventForOtherBranchException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.Map;

/**
 * What happens when a delivery fails.
 *
 * <pre>
 * dc.branch-a --> listener A --> POST branch A /events
 *                     | branch down / 5xx: retry with back-off (later records wait: order is kept)
 *                     | still failing, or rejected (4xx), or unreadable, or for another branch
 *                     v
 *                 dc.branch-a.DLT  (original record + headers saying why), then move on
 * </pre>
 *
 * Spring Boot plugs the {@link DefaultErrorHandler} bean into every {@code @KafkaListener}.
 */
@Configuration
class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(ListenerProperties props, KafkaProperties kafkaProperties) {
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(deadLetterTemplate(kafkaProperties),
                (record, ex) -> new TopicPartition(props.deadLetterTopic(), record.partition()));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(props.retry().maxRetries());
        backOff.setInitialInterval(props.retry().initialInterval().toMillis());
        backOff.setMultiplier(props.retry().multiplier());
        backOff.setMaxInterval(props.retry().maxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            log.error("Giving up on {}-{}@{}; sending it to {}: {}", record.topic(), record.partition(), record.offset(),
                    props.deadLetterTopic(), reason(ex));
            toDeadLetterTopic.accept(record, ex);
        }, backOff);
        // Retrying cannot fix these; dead-letter them on the first failure.
        handler.addNotRetryableExceptions(BranchRejectedException.class, EventForOtherBranchException.class);
        return handler;
    }

    /**
     * The most useful message in the cause chain: our own exceptions say what happened in plain words
     * ("Branch not reachable: ..."); the innermost cause (e.g. a ConnectException) often has no message.
     */
    static String reason(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getName().startsWith("com.branchdc.") && t.getMessage() != null) {
                return t.getMessage();
            }
        }
        return String.valueOf(NestedExceptionUtils.getMostSpecificCause(ex));
    }

    /**
     * Producer for the dead-letter topic only. A readable record is written back as JSON; an unreadable
     * one (bad JSON) has no object, so its original bytes are copied unchanged.
     */
    private static KafkaTemplate<String, Object> deadLetterTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties();
        JacksonJsonSerializer<Object> json = new JacksonJsonSerializer<>();
        json.setAddTypeInfo(false);
        DelegatingByTypeSerializer values = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                BranchSyncEvent.class, json));
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config, new StringSerializer(), values));
    }
}
