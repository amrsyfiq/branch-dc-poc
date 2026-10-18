package com.branchdc.listener;

import com.branchdc.events.PassEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads this listener's branch topic (and only that one) and hands each event to the branch.
 *
 * <p>Every copy of this listener (every pod, and every consumer thread in a pod) joins the same group,
 * {@code listener-a}, so Kafka shares the topic's partitions between them: that is how adding pods adds
 * delivery capacity, up to one consumer per partition. Failures are handled by the error handler in
 * {@link KafkaConfig}.
 */
@Component
public class BranchEventListener {

    private static final Logger log = LoggerFactory.getLogger(BranchEventListener.class);

    private final ListenerProperties props;
    private final BranchClient branch;

    public BranchEventListener(ListenerProperties props, BranchClient branch) {
        this.props = props;
        this.branch = branch;
    }

    /** Used by the annotation below ({@code __listener} is this bean). */
    public String getTopic() {
        return props.topic();
    }

    /** {@code listener-a}: Kafka stores this listener's read position under this name, shared by all its copies. */
    public String getGroupId() {
        return "listener-" + props.branchId().toLowerCase(java.util.Locale.ROOT);
    }

    @KafkaListener(topics = "#{__listener.topic}", groupId = "#{__listener.groupId}")
    public void onEvent(ConsumerRecord<String, PassEvent> record) {
        PassEvent event = record.value();
        // Defence in depth: never forward another branch's data, even if it somehow lands on this topic.
        if (!props.branchId().equalsIgnoreCase(event.branchId())) {
            throw new EventForOtherBranchException(props.branchId(), event.branchId());
        }
        branch.deliver(event);
        // One line per delivered pass: which pass (never who), and where it came from in Kafka.
        log.atInfo().addKeyValue("applicationNo", event.applicationNo()).addKeyValue("seq", event.seq())
                .addKeyValue("eventType", event.type()).addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .log("Delivered {} {} (seq {}) from {}-{}@{} to branch {}", event.type(), event.applicationNo(),
                        event.seq(), record.topic(), record.partition(), record.offset(), props.branchId());
    }

    public static class EventForOtherBranchException extends RuntimeException {
        public EventForOtherBranchException(String listenerBranch, String eventBranch) {
            super("Listener for branch " + listenerBranch + " received an event for branch " + eventBranch);
        }
    }
}
