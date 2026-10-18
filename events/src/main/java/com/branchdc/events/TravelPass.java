package com.branchdc.events;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An approved (or revoked) travel pass, as the DC sends it to the entry checkpoint the traveller will use.
 *
 * <p>Holds personal data. {@link #toString()} leaves it out on purpose: a record's default {@code toString}
 * prints every field, so one careless {@code log.info("{}", pass)} would put passport numbers in the logs.
 * Logs identify a pass by its application number (on the event), never by who the traveller is.
 */
public record TravelPass(String passportNo, String fullName, String nationality, LocalDate dateOfBirth,
                         Type passType, LocalDate validFrom, LocalDate validUntil, Status status) {

    public enum Type { TOURIST, BUSINESS, STUDENT, TRANSIT }

    /** A revoked pass stays at the checkpoint, so the officer sees "revoked" instead of "unknown". */
    public enum Status { APPROVED, REVOKED }

    public TravelPass {
        Objects.requireNonNull(passportNo, "passportNo");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(nationality, "nationality");
        Objects.requireNonNull(dateOfBirth, "dateOfBirth");
        Objects.requireNonNull(passType, "passType");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(status, "status");
        if (validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil is before validFrom");
        }
    }

    /** Can the traveller enter with this pass on that day? */
    public boolean validOn(LocalDate day) {
        return status == Status.APPROVED && !day.isBefore(validFrom) && !day.isAfter(validUntil);
    }

    @Override
    public String toString() {
        return "TravelPass[" + passType + ", " + status + ", " + validFrom + ".." + validUntil + ", personal data hidden]";
    }
}
