package com.branchdc.dc;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Reads a whole topic (every partition, from the beginning) in tests. */
final class TopicReader {

    private TopicReader() {
    }

    static List<ConsumerRecord<String, String>> readAll(String topic) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = partitions(consumer, topic);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }

    /** Total number of records ever written to the topic (sum of the partitions' end offsets). */
    static long endOffsetSum(String topic) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            return consumer.endOffsets(partitions(consumer, topic)).values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private static List<TopicPartition> partitions(KafkaConsumer<String, String> consumer, String topic) {
        return consumer.partitionsFor(topic).stream().map(p -> new TopicPartition(topic, p.partition())).toList();
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }
}
