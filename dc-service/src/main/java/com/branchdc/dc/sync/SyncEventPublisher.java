package com.branchdc.dc.sync;

import com.branchdc.events.BranchSyncEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Sends a {@link BranchSyncEvent} to the branch's own topic once the DB transaction has committed.
 *
 * <p>Record key = branch id, so every event of one branch lands in the same partition, in order.
 * The send is awaited (bounded by the producer timeouts) so the HTTP response can say whether Kafka
 * accepted the event; the log row is then marked PUBLISHED or FAILED.
 */
@Component
public class SyncEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SyncEventPublisher.class);

    /** Longer than the producer's delivery.timeout.ms, so the producer reports the real error first. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

    private final KafkaTemplate<String, BranchSyncEvent> kafka;
    private final SyncLogStatus status;

    public SyncEventPublisher(KafkaTemplate<String, BranchSyncEvent> kafka, SyncLogStatus status) {
        this.kafka = kafka;
        this.status = status;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(BranchSyncEvent event) {
        String topic = BranchSyncEvent.topicFor(event.branchId());
        String eventId = event.eventId().toString();
        try {
            SendResult<String, BranchSyncEvent> result = kafka.send(topic, event.branchId(), event)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Published {} {} for branch {} ({} products) to {}@{}",
                    event.type(), eventId, event.branchId(), event.products().size(),
                    topic, result.getRecordMetadata().offset());
            status.markPublished(eventId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String reason = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            log.error("Could not publish {} {} for branch {}: {}", event.type(), eventId, event.branchId(), reason);
            status.markFailed(eventId, reason);
        }
    }
}
