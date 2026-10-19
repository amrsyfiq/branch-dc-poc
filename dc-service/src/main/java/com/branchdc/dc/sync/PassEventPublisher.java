package com.branchdc.dc.sync;

import com.branchdc.dc.pass.PassService;
import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends a request's events to the branch's topic once the DB transaction has committed, one record per
 * pass with the application number as the key.
 *
 * <p>A chunk at a time ({@code dc.publish.chunk-size}, 1,000): load those passes, hand their records to the
 * producer (it batches them), await every acknowledgement, record the progress, next chunk. So memory stays the
 * same whatever the size of a checkpoint (all at once, a sync of 43,144 passes ran the DC out of memory), and a
 * request that is cut off halfway shows how many Kafka had confirmed.
 *
 * <p>If a send fails right away (e.g. Kafka is unreachable and the producer gave up waiting for it), the rest
 * are not attempted: each would wait the same timeout again. The log row then says FAILED, with how many Kafka
 * <b>confirmed</b>. A record that timed out may still have been written (the broker stored it, the confirmation
 * came too late), so the branch can receive more than that. Either way, syncing again repairs the branch:
 * upserts with newer seqs overwrite.
 */
@Component
public class PassEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PassEventPublisher.class);

    /** Per chunk. Longer than the producer's delivery.timeout.ms (60 s), so the producer reports the real error first. */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(75);

    private final KafkaTemplate<String, PassEvent> kafka;
    private final SyncLogStatus status;
    private final PassService passes;
    private final int chunkSize;

    public PassEventPublisher(KafkaTemplate<String, PassEvent> kafka, SyncLogStatus status, PassService passes,
                              @Value("${dc.publish.chunk-size:1000}") int chunkSize) {
        this.kafka = kafka;
        this.status = status;
        this.passes = passes;
        this.chunkSize = chunkSize;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(PublishRequest request) {
        String topic = PassEvent.topicFor(request.branchCode());
        long started = System.nanoTime();
        int total = request.size();
        int published = 0;
        String error = null;
        for (int from = 0; from < total && error == null; from += chunkSize) {
            int to = Math.min(total, from + chunkSize);
            Chunk chunk = sendChunk(topic, request, from, to);
            published += chunk.published();
            error = chunk.error();
            if (error == null && to < total) {
                status.markProgress(request.requestId(), published);
                log.atInfo().addKeyValue("requestId", request.requestId()).addKeyValue("published", published)
                        .addKeyValue("events", total)
                        .log("{} for branch {}: {} of {} events published", request.kind(), request.branchCode(),
                                published, total);
            }
        }

        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
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

    /** Sends events {@code from} (inclusive) to {@code to} (exclusive) and awaits their acknowledgements. */
    private Chunk sendChunk(String topic, PublishRequest request, int from, int to) {
        List<CompletableFuture<SendResult<String, PassEvent>>> sends = new ArrayList<>(to - from);
        String error = null;
        try {
            Map<String, TravelPass> data = request.type() == PassEvent.Type.UPSERT
                    ? passes.travelPasses(request.applicationNos().subList(from, to))
                    : Map.of(); // a delete carries no pass data
            for (int i = from; i < to; i++) {
                PassEvent event = event(request, i, data);
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
        return new Chunk(published, error);
    }

    /** The i-th event of the request: its pass with its sequence number. */
    private static PassEvent event(PublishRequest request, int i, Map<String, TravelPass> data) {
        String applicationNo = request.applicationNos().get(i);
        long seq = request.seqs().get(i);
        if (request.type() == PassEvent.Type.DELETE) {
            return PassEvent.delete(seq, request.branchCode(), request.occurredAt(), applicationNo);
        }
        TravelPass pass = data.get(applicationNo);
        if (pass == null) { // the DC never deletes passes, so this means a bug
            throw new IllegalStateException("Pass " + applicationNo + " is not in the DC database");
        }
        return PassEvent.upsert(seq, request.branchCode(), request.occurredAt(), applicationNo, pass);
    }

    private static String reason(Throwable e) {
        return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
    }

    private record Chunk(int published, String error) {
    }
}
