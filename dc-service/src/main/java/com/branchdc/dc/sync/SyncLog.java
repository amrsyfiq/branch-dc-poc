package com.branchdc.dc.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One sync, purge or rush request: how many events it produced and whether Kafka accepted them all. */
@Entity
@Table(name = "sync_log")
public class SyncLog {

    public enum Kind { SYNC, PURGE, RUSH }

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "branch_code")
    private String branchCode;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(name = "event_count")
    private int eventCount;

    @Column(name = "first_seq")
    private Long firstSeq;

    @Column(name = "last_seq")
    private Long lastSeq;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "published_count")
    private int publishedCount;

    private String error;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected SyncLog() {
        // for JPA
    }

    static SyncLog pending(UUID requestId, String branchCode, Kind kind, int eventCount, Long firstSeq, Long lastSeq,
                           Instant createdAt) {
        SyncLog log = new SyncLog();
        log.requestId = requestId;
        log.branchCode = branchCode;
        log.kind = kind;
        log.eventCount = eventCount;
        log.firstSeq = firstSeq;
        log.lastSeq = lastSeq;
        log.status = Status.PENDING;
        log.createdAt = createdAt;
        return log;
    }

    void markPublished(int count, Instant at) {
        status = Status.PUBLISHED;
        publishedCount = count;
        publishedAt = at;
        error = null;
    }

    void markFailed(int published, String reason) {
        status = Status.FAILED;
        publishedCount = published;
        error = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public Kind getKind() {
        return kind;
    }

    public int getEventCount() {
        return eventCount;
    }

    public Long getFirstSeq() {
        return firstSeq;
    }

    public Long getLastSeq() {
        return lastSeq;
    }

    public Status getStatus() {
        return status;
    }

    public int getPublishedCount() {
        return publishedCount;
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
