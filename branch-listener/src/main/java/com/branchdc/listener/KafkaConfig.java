package com.branchdc.listener;

import com.branchdc.events.PassEvent;
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
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * What happens when a delivery fails.
 *
 * <pre>
 * dc.branch-a --> listener A --> POST branch A /events
 *                     | branch down / 5xx: retry with back-off for ~30 min (later records wait: order is kept)
 *                     | still failing after that, or rejected (4xx), or unreadable, or for another branch
 *                     v
 *                 dc.branch-a.DLT  (original record + headers saying why), then move on
 * </pre>
 *
 * Spring Boot plugs the {@link DefaultErrorHandler} bean into every {@code @KafkaListener}.
 */
@Configuration
class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** How long a stopping pod waits for the record in progress (Kubernetes allows 30 s before it kills the pod). */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Every listener container: log which partitions it gets and loses ({@link PartitionLogger}), and on
     * shutdown (scale-down, rolling update) finish the current record and commit before leaving the group,
     * so the partitions move to another pod without redelivering much.
     */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> listenerContainerCustomizer() {
        return container -> {
            container.getContainerProperties().setConsumerRebalanceListener(new PartitionLogger());
            container.getContainerProperties().setShutdownTimeout(SHUTDOWN_TIMEOUT.toMillis());
        };
    }

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
        // A WARN line per failed attempt: the logs show a branch outage while it happens, not only at the end.
        handler.setRetryListeners((record, ex, attempt) -> log.atWarn()
                .addKeyValue("partition", record.partition()).addKeyValue("offset", record.offset())
                .addKeyValue("attempt", attempt)
                .log("Delivery of {}-{}@{} failed (attempt {} of at most {}): {}", record.topic(), record.partition(),
                        record.offset(), attempt, props.retry().maxRetries() + 1, reason(ex)));
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
                PassEvent.class, json));
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config, new StringSerializer(), values));
    }
}
