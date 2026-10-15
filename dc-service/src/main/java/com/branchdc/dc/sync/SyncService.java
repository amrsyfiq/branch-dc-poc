package com.branchdc.dc.sync;

import com.branchdc.dc.catalog.CatalogService;
import com.branchdc.events.BranchSyncEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Turns "sync branch A" / "purge branch A" into an event for branch A's topic.
 *
 * <p>The log row is written in a transaction; the event is handed to {@link SyncEventPublisher}, which
 * sends it to Kafka only <b>after</b> that transaction commits. So a rolled-back request never reaches a
 * branch. (Known gap, no outbox: if the app dies between commit and send, the row stays PENDING and the
 * event is never sent. Syncing again fixes it, because a snapshot is a full replace.)
 */
@Service
public class SyncService {

    private final CatalogService catalog;
    private final SyncLogRepository logs;
    private final ApplicationEventPublisher springEvents;

    public SyncService(CatalogService catalog, SyncLogRepository logs, ApplicationEventPublisher springEvents) {
        this.catalog = catalog;
        this.logs = logs;
        this.springEvents = springEvents;
    }

    /** Sends the branch its complete slice. Returns the event id. */
    @Transactional
    public String requestSnapshot(String branchCode) {
        String code = catalog.requireBranch(branchCode).getCode();
        return record(BranchSyncEvent.snapshot(code, now(), catalog.sliceOf(code)));
    }

    /** Tells the branch to delete all its data. Returns the event id. */
    @Transactional
    public String requestPurge(String branchCode) {
        String code = catalog.requireBranch(branchCode).getCode();
        return record(BranchSyncEvent.purge(code, now()));
    }

    @Transactional(readOnly = true)
    public SyncLogView find(String eventId) {
        return logs.findById(eventId).map(SyncLogView::of).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<SyncLogView> recent(int limit) {
        return logs.findByOrderByCreatedAtDesc(Limit.of(limit)).stream().map(SyncLogView::of).toList();
    }

    private String record(BranchSyncEvent event) {
        logs.save(SyncLog.pending(event));
        springEvents.publishEvent(event); // delivered to Kafka after commit, see SyncEventPublisher
        return event.eventId().toString();
    }

    /** Oracle TIMESTAMP keeps microseconds; truncating keeps the event and the stored row identical. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public record SyncLogView(String eventId, String branch, BranchSyncEvent.Type type, int productCount,
                              SyncLog.Status status, String error, Instant createdAt, Instant publishedAt) {
        static SyncLogView of(SyncLog l) {
            return new SyncLogView(l.getEventId(), l.getBranchCode(), l.getType(), l.getProductCount(),
                    l.getStatus(), l.getError(), l.getCreatedAt(), l.getPublishedAt());
        }
    }
}
