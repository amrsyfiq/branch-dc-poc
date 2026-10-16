package com.branchdc.branch.store;

import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Writes one event into {@code branch_pass} with a single statement: insert the pass, or update it, but only
 * if the event is newer than what the row has ({@code WHERE event_seq < new seq}).
 *
 * <p>Why this is safe with many listener pods and many branch pods at once: PostgreSQL runs the whole
 * "insert or update if newer" as one atomic step and locks only that one row. Two deliveries of the same pass
 * at the same moment are applied one after the other, and whichever order they run in, the row ends with the
 * newest version. No table lock, so deliveries of different passes run in parallel.
 *
 * <p>Plain JDBC rather than JPA here: the conditional upsert is PostgreSQL SQL, and a delete binds NULLs, which
 * need explicit SQL types (a JPA native query can't always tell PostgreSQL what type a NULL is).
 */
@Component
class PassWriter {

    private static final String UPSERT_IF_NEWER = """
            INSERT INTO branch_pass AS p (application_no, passport_no, full_name, nationality, date_of_birth,
                                          pass_type, valid_from, valid_until, status, deleted,
                                          event_seq, event_id, occurred_at, received_at)
            VALUES (:applicationNo, :passportNo, :fullName, :nationality, :dateOfBirth,
                    :passType, :validFrom, :validUntil, :status, :deleted,
                    :seq, :eventId, :occurredAt, :receivedAt)
            ON CONFLICT (application_no) DO UPDATE SET
                passport_no = EXCLUDED.passport_no, full_name = EXCLUDED.full_name,
                nationality = EXCLUDED.nationality, date_of_birth = EXCLUDED.date_of_birth,
                pass_type = EXCLUDED.pass_type, valid_from = EXCLUDED.valid_from,
                valid_until = EXCLUDED.valid_until, status = EXCLUDED.status, deleted = EXCLUDED.deleted,
                event_seq = EXCLUDED.event_seq, event_id = EXCLUDED.event_id,
                occurred_at = EXCLUDED.occurred_at, received_at = EXCLUDED.received_at
            WHERE p.event_seq < EXCLUDED.event_seq
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PassWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the row now holds this event; false if the row already had this or a newer one */
    boolean upsertIfNewer(PassEvent event, Instant receivedAt) {
        TravelPass pass = event.pass(); // null for a delete: the row becomes a tombstone
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("applicationNo", event.applicationNo(), Types.VARCHAR)
                .addValue("passportNo", pass == null ? null : pass.passportNo(), Types.VARCHAR)
                .addValue("fullName", pass == null ? null : pass.fullName(), Types.VARCHAR)
                .addValue("nationality", pass == null ? null : pass.nationality(), Types.VARCHAR)
                .addValue("dateOfBirth", pass == null ? null : pass.dateOfBirth(), Types.DATE)
                .addValue("passType", pass == null ? null : pass.passType().name(), Types.VARCHAR)
                .addValue("validFrom", pass == null ? null : pass.validFrom(), Types.DATE)
                .addValue("validUntil", pass == null ? null : pass.validUntil(), Types.DATE)
                .addValue("status", pass == null ? null : pass.status().name(), Types.VARCHAR)
                .addValue("deleted", event.type() == PassEvent.Type.DELETE, Types.BOOLEAN)
                .addValue("seq", event.seq(), Types.BIGINT)
                .addValue("eventId", event.eventId(), Types.OTHER)
                .addValue("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("receivedAt", receivedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbc.update(UPSERT_IF_NEWER, params) == 1;
    }

    /** The seq the row has now, if there is a row. */
    Optional<Long> currentSeq(String applicationNo) {
        List<Long> seq = jdbc.queryForList("SELECT event_seq FROM branch_pass WHERE application_no = :applicationNo",
                new MapSqlParameterSource("applicationNo", applicationNo), Long.class);
        return seq.stream().findFirst();
    }
}
