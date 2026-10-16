package com.branchdc.dc.sync;

import com.branchdc.dc.pass.CheckpointRepository;
import com.branchdc.events.PassEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.stream.Stream;

/**
 * One topic per branch, plus its dead-letter topic, for every checkpoint in the DC database:
 *
 * <pre>
 * dc.branch-a      DC -> listener(s) A     dc.branch-a.DLT  listener A could not deliver
 * dc.branch-b      DC -> listener(s) B     dc.branch-b.DLT  listener B could not deliver
 * </pre>
 *
 * The partition count is the most listener consumers (pods x threads) that can work on one branch at the
 * same time: Kafka gives each partition to one consumer of the group. The dead-letter topic has as many,
 * because a failed record goes to the same partition number there. KafkaAdmin creates missing topics at
 * startup (the broker has auto-create turned off) and adds partitions to an existing topic that has fewer.
 * (Real life: topics belong to the Kafka team; the count is agreed with them before the holiday season.)
 */
@Configuration
class KafkaTopicsConfig {

    @Bean
    KafkaAdmin.NewTopics branchTopics(CheckpointRepository checkpoints, @Value("${dc.topics.partitions:6}") int partitions) {
        NewTopic[] topics = checkpoints.findAllByOrderByCodeAsc().stream()
                .flatMap(c -> Stream.of(PassEvent.topicFor(c.getCode()), PassEvent.deadLetterTopicFor(c.getCode())))
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(1).build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
