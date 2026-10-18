package com.branchdc.dc.sync;

import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the DC does when Kafka does not accept the events (checked live too: Kafka stopped -> FAILED). */
class PassEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, PassEvent> kafka = mock(KafkaTemplate.class);
    private final SyncLogStatus status = mock(SyncLogStatus.class);
    private final PassEventPublisher publisher = new PassEventPublisher(kafka, status);

    private final TravelPass pass = new TravelPass("X12345678", "Test Person", "SGP", LocalDate.of(1990, 1, 1),
            TravelPass.Type.TOURIST, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 14), TravelPass.Status.APPROVED);
    private final List<PassEvent> events = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> PassEvent.upsert(i, "A", Instant.now(), "APP-2026-00000" + i, pass)).toList();
    private final PublishRequest request = new PublishRequest(UUID.randomUUID(), SyncLog.Kind.SYNC, "A", events);

    @Test
    void sendFailingRightAwayStopsTheRequestAndMarksItFailedWithTheReason() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Topic dc.branch-a not present in metadata after 5000 ms.")));

        publisher.publish(request);

        // the other four would each wait the same 5 s for nothing
        verify(kafka, times(1)).send(anyString(), anyString(), any());
        verify(status).markFailed(eq(request.requestId()), eq(0), eq("Topic dc.branch-a not present in metadata after 5000 ms."));
        verify(status, never()).markPublished(any(), anyInt(), any());
    }
}
