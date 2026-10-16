package com.branchdc.branch.store;

import com.branchdc.events.TravelPass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One pass of this branch's copy, for reading. Rows are written only by {@link PassWriter} (one conditional
 * SQL statement), never through JPA, hence {@code @Immutable}.
 */
@Entity
@Immutable
@Table(name = "branch_pass")
public class BranchPass {

    @Id
    @Column(name = "application_no")
    private String applicationNo;

    @Column(name = "passport_no")
    private String passportNo;

    @Column(name = "full_name")
    private String fullName;

    private String nationality;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "pass_type")
    private TravelPass.Type passType;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    private TravelPass.Status status;

    private boolean deleted;

    @Column(name = "event_seq")
    private long eventSeq;

    @Column(name = "received_at")
    private Instant receivedAt;

    protected BranchPass() {
        // for JPA
    }

    /** Only for live rows (a tombstone has no pass). */
    TravelPass toTravelPass() {
        return new TravelPass(passportNo, fullName, nationality, dateOfBirth, passType, validFrom, validUntil, status);
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public long getEventSeq() {
        return eventSeq;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
