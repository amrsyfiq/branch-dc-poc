package com.branchdc.dc.sync;

import com.branchdc.events.PassEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends a request's events to the branch's topic once the DB transaction has committed, one record per
 * pass with the application number as the key.
 *
 * <p>All records are handed to the producer first (it batches them), then every acknowledgement is awaited,
 * so the HTTP response can say whether Kafka accepted them. If a send fails right away (e.g. Kafka is
 * unreachable and the producer gave up waiting for it), the rest are not attempted: each would wait the
 * same timeout again. The log row then says FAILED, with how many Kafka <b>confirmed</b>. A record that timed
 * out may still have been written (the broker stored it, the confirmation came too late), so the branch can
 * receive more than that. Either way, syncing again repairs the branch: upserts with newer seqs overwrite.
 */
@Component
public class PassEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PassEventPublisher.class);

    /** Longer than the producer's delivery.timeout.ms (60 s), so the producer reports the real error first. */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(75);

    private final KafkaTemplate<String, PassEvent> kafka;
    private final SyncLogStatus status;

    public PassEventPublisher(KafkaTemplate<String, PassEvent> kafka, SyncLogStatus status) {
        this.kafka = kafka;
        this.status = status;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(PublishRequest request) {
        String topic = PassEvent.topicFor(request.branchCode());
        long started = System.nanoTime();
        List<CompletableFuture<SendResult<String, PassEvent>>> sends = new ArrayList<>(request.events().size());
        String error = null;
        try {
            for (PassEvent event : request.events()) {
                CompletableFuture<SendResult<String, PassEvent>> send = kafka.send(topic, event.applicationNo(), event);
                sends.add(send);
                if (send.isCompletedExceptionally()) {
                    break; // failed before it was even sent: the next ones would fail the same way, slowly
                }
            }
        } catch (RuntimeException e) {
            error = reason(e);
        }

        int published = 0;
        long deadline = System.nanoTime() + ACK_TIMEOUT.toNanos();
        for (CompletableFuture<SendResult<String, PassEvent>> send : sends) {
            try {
                SendResult<String, PassEvent> result = send.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                published++;
                PassEvent event = result.getProducerRecord().value();
                log.atDebug().addKeyValue("applicationNo", event.applicationNo()).addKeyValue("seq", event.seq())
                        .addKeyValue("partition", result.getRecordMetadata().partition())
                        .addKeyValue("offset", result.getRecordMetadata().offset())
                        .log("Published {} {} seq {}", event.type(), event.applicationNo(), event.seq());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (error == null) {
                    error = reason(e);
                }
            }
        }

        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int total = request.events().size();
        if (published == total && error == null) {
            log.atInfo().addKeyValue("requestId", request.requestId()).addKeyValue("branch", request.branchCode())
                    .addKeyValue("kind", request.kind()).addKeyValue("events", total).addKeyValue("durationMs", millis)
                    .log("{} for branch {}: {} events published to {} in {} ms", request.kind(), request.branchCode(),
                            total, topic, millis);
            status.markPublished(request.requestId(), published, Instant.now().truncatedTo(ChronoUnit.MICROS));
        } else {
            log.atError().addKeyValue("requestId", request.requestId()).addKeyValue("branch", request.branchCode())
                    .addKeyValue("kind", request.kind()).addKeyValue("events", total).addKeyValue("published", published)
                    .log("{} for branch {}: only {} of {} events published to {}: {}", request.kind(),
                            request.branchCode(), published, total, topic, error);
            status.markFailed(request.requestId(), published, error);
        }
    }

    private static String reason(Throwable e) {
        return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
    }
}
