package com.branchdc.dc.sync;

import com.branchdc.events.BranchSyncEvent;
import com.branchdc.events.ProductPrice;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the DC does when Kafka does not accept an event (checked live too: Kafka stopped -> FAILED). */
class SyncEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, BranchSyncEvent> kafka = mock(KafkaTemplate.class);
    private final SyncLogStatus status = mock(SyncLogStatus.class);
    private final SyncEventPublisher publisher = new SyncEventPublisher(kafka, status);

    private final BranchSyncEvent event = BranchSyncEvent.snapshot("A", Instant.now(),
            List.of(new ProductPrice("P-001", "Coffee", new BigDecimal("1.00"), "MYR")));

    @Test
    void failedSendMarksTheLogRowFailedWithTheReason() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Expiring 1 record(s)")));

        publisher.publish(event);

        verify(status).markFailed(eq(event.eventId().toString()), eq("Expiring 1 record(s)"));
        verify(status, never()).markPublished(anyString(), any());
    }
}
