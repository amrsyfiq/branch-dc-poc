package com.branchdc.dc.sync;

import com.branchdc.events.BranchSyncEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One sync or purge request and whether Kafka accepted the event. */
@Entity
@Table(name = "sync_log")
public class SyncLog {

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "branch_code")
    private String branchCode;

    @Enumerated(EnumType.STRING)
    private BranchSyncEvent.Type type;

    @Column(name = "product_count")
    private int productCount;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected SyncLog() {
        // for JPA
    }

    static SyncLog pending(BranchSyncEvent event) {
        SyncLog log = new SyncLog();
        log.eventId = event.eventId().toString();
        log.branchCode = event.branchId();
        log.type = event.type();
        log.productCount = event.products().size();
        log.status = Status.PENDING;
        log.createdAt = event.occurredAt();
        return log;
    }

    void markPublished(Instant at) {
        status = Status.PUBLISHED;
        publishedAt = at;
        error = null;
    }

    void markFailed(String reason) {
        status = Status.FAILED;
        error = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public String getEventId() {
        return eventId;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public BranchSyncEvent.Type getType() {
        return type;
    }

    public int getProductCount() {
        return productCount;
    }

    public Status getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
