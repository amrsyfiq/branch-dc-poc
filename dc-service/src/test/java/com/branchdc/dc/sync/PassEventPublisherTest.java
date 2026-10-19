package com.branchdc.dc.sync;

import com.branchdc.dc.pass.PassService;
import com.branchdc.events.PassEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the DC does when Kafka does not accept the events (checked live too: Kafka stopped -> FAILED). Purges (deletes)
 * here, so no pass data needs loading; chunks of 2, so a request of 5 is sent as 2 + 2 + 1.
 */
class PassEventPublisherTest {

    private static final TimeoutException NO_METADATA = new TimeoutException("Topic dc.branch-a not present in metadata after 5000 ms.");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, PassEvent> kafka = mock(KafkaTemplate.class);
    private final SyncLogStatus status = mock(SyncLogStatus.class);
    private final PassEventPublisher publisher = new PassEventPublisher(kafka, status, mock(PassService.class), 2);

    private final PublishRequest request = new PublishRequest(UUID.randomUUID(), SyncLog.Kind.PURGE, "A",
            PassEvent.Type.DELETE, Instant.now(),
            IntStream.rangeClosed(1, 5).mapToObj(i -> "APP-2026-00000" + i).toList(),
            LongStream.rangeClosed(101, 105).boxed().toList());

    @Test
    void sendFailingRightAwayStopsTheRequestAndMarksItFailedWithTheReason() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(NO_METADATA));

        publisher.publish(request);

        // the other four would each wait the same 5 s for nothing
        verify(kafka, times(1)).send(anyString(), anyString(), any());
        verify(status).markFailed(eq(request.requestId()), eq(0), eq(NO_METADATA.getMessage()));
        verify(status, never()).markPublished(any(), anyInt(), any());
    }

    @Test
    void eachConfirmedChunkIsRecordedSoACutOffRequestShowsHowManyKafkaHad() {
        // the first chunk (2 events) goes through, the second chunk's first send fails
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(confirmed(), confirmed(), CompletableFuture.failedFuture(NO_METADATA));

        publisher.publish(request);

        verify(kafka, times(3)).send(anyString(), anyString(), any());
        verify(status).markProgress(request.requestId(), 2);
        verify(status).markFailed(eq(request.requestId()), eq(2), eq(NO_METADATA.getMessage()));
        verify(status, never()).markPublished(any(), anyInt(), any());
    }

    @Test
    void allChunksConfirmedMarksTheRequestPublished() {
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(call -> confirmed());

        publisher.publish(request);

        verify(kafka, times(5)).send(anyString(), anyString(), any());
        verify(status).markProgress(request.requestId(), 2);
        verify(status).markProgress(request.requestId(), 4);
        verify(status).markPublished(eq(request.requestId()), eq(5), any());
        verify(status, never()).markFailed(any(), anyInt(), any());
    }

    private static CompletableFuture<SendResult<String, PassEvent>> confirmed() {
        PassEvent event = PassEvent.delete(1, "A", Instant.now(), "APP-2026-000001");
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("dc.branch-a", event.applicationNo(), event),
                new RecordMetadata(new TopicPartition("dc.branch-a", 0), 0, 0, 0, 0, 0)));
    }
}
