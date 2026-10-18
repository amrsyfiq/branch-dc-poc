package com.branchdc.dc.pass;

import com.branchdc.events.TravelPass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;

/** One travel pass in the DC database. Which branch gets it: {@link #getCheckpointCode()}. */
@Entity
@Table(name = "travel_pass")
public class Pass implements Persistable<String> {

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

    @Column(name = "checkpoint_code")
    private String checkpointCode;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    private TravelPass.Status status;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** New passes are only ever inserted (the rush), so save() can skip the "does it exist?" select. */
    @Transient
    private boolean isNew;

    protected Pass() {
        // for JPA
    }

    static Pass create(String applicationNo, String checkpointCode, TravelPass pass, Instant now) {
        Pass p = new Pass();
        p.applicationNo = applicationNo;
        p.checkpointCode = checkpointCode;
        p.passportNo = pass.passportNo();
        p.fullName = pass.fullName();
        p.nationality = pass.nationality();
        p.dateOfBirth = pass.dateOfBirth();
        p.passType = pass.passType();
        p.validFrom = pass.validFrom();
        p.validUntil = pass.validUntil();
        p.status = pass.status();
        p.updatedAt = now;
        p.isNew = true;
        return p;
    }

    /** What the branch receives (everything except the DC's own bookkeeping). */
    public TravelPass toTravelPass() {
        return new TravelPass(passportNo, fullName, nationality, dateOfBirth, passType, validFrom, validUntil, status);
    }

    @Override
    public String getId() {
        return applicationNo;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public String getCheckpointCode() {
        return checkpointCode;
    }

    public TravelPass.Status getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
