package com.branchdc.dc.sync;

import com.branchdc.dc.catalog.BranchRepository;
import com.branchdc.events.BranchSyncEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.stream.Stream;

/**
 * One topic per branch, plus its dead-letter topic, for every branch in the DC database:
 *
 * <pre>
 * dc.branch-a      DC -> listener A        dc.branch-a.DLT  listener A could not deliver
 * dc.branch-b      DC -> listener B        dc.branch-b.DLT  listener B could not deliver
 * </pre>
 *
 * One partition each: a branch's events must be applied in order, and there is one listener per branch.
 * KafkaAdmin creates missing topics at startup (the broker has auto-create turned off).
 */
@Configuration
class KafkaTopicsConfig {

    @Bean
    KafkaAdmin.NewTopics branchTopics(BranchRepository branches) {
        NewTopic[] topics = branches.findAllByOrderByCodeAsc().stream()
                .flatMap(b -> Stream.of(BranchSyncEvent.topicFor(b.getCode()), BranchSyncEvent.deadLetterTopicFor(b.getCode())))
                .map(name -> TopicBuilder.name(name).partitions(1).replicas(1).build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}
