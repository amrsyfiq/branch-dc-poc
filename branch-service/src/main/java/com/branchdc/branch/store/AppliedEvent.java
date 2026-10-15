package com.branchdc.branch.store;

import com.branchdc.events.BranchSyncEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** An event this branch has received, and whether it changed the data (APPLIED) or was too old (STALE). */
@Entity
@Table(name = "applied_event")
public class AppliedEvent implements Persistable<String> {

    public enum Outcome { APPLIED, STALE }

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Enumerated(EnumType.STRING)
    private BranchSyncEvent.Type type;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    @Column(name = "product_count")
    private int productCount;

    @Transient
    private boolean isNew = true;

    protected AppliedEvent() {
        // for JPA
    }

    static AppliedEvent of(BranchSyncEvent event, Outcome outcome, Instant receivedAt) {
        AppliedEvent e = new AppliedEvent();
        e.eventId = event.eventId().toString();
        e.type = event.type();
        e.occurredAt = event.occurredAt();
        e.receivedAt = receivedAt;
        e.outcome = outcome;
        e.productCount = event.products().size();
        return e;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public BranchSyncEvent.Type getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }
}
