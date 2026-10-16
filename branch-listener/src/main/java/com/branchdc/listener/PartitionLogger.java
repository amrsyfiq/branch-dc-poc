package com.branchdc.listener;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs which partitions this consumer gets and loses. After scaling up or down (or when a pod dies), these
 * lines show how Kafka shared the topic out again: each pod's log says which partitions it now reads.
 *
 * <p>Shared by the consumer threads of this pod; they never own the same partition, so one set is enough.
 */
class PartitionLogger implements ConsumerAwareRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(PartitionLogger.class);

    private final Set<TopicPartition> owned = ConcurrentHashMap.newKeySet();

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        owned.addAll(partitions);
        if (!partitions.isEmpty()) {
            log.atInfo().addKeyValue("partitions", numbers(partitions))
                    .log("Assigned partitions {}; now reading {}", numbers(partitions), numbers(consumer.assignment()));
        }
    }

    @Override
    public void onPartitionsRevokedAfterCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        owned.removeAll(partitions);
        if (!partitions.isEmpty()) {
            log.atInfo().addKeyValue("partitions", numbers(partitions))
                    .log("Gave up partitions {} (offsets committed): another consumer takes over", numbers(partitions));
        }
    }

    /**
     * Called when partitions were taken away without a clean hand-over (e.g. this consumer was too slow and was
     * removed from the group). Kafka also calls it on shutdown for partitions just given up cleanly; those were
     * committed already, so only the ones still owned are worth a warning.
     */
    @Override
    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        List<TopicPartition> stillOwned = partitions.stream().filter(owned::remove).toList();
        if (!stillOwned.isEmpty()) {
            log.atWarn().addKeyValue("partitions", numbers(stillOwned))
                    .log("Lost partitions {} without committing: their latest records will be delivered again "
                            + "(the branch ignores the duplicates)", numbers(stillOwned));
        }
    }

    private static List<Integer> numbers(Collection<TopicPartition> partitions) {
        return partitions.stream().map(TopicPartition::partition).sorted().toList();
    }
}
