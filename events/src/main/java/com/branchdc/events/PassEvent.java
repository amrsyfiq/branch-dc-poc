package com.branchdc.events;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One change to one travel pass, from the DC to one branch, on that branch's topic ({@code dc.branch-a}, ...).
 * The Kafka record key is {@link #applicationNo()}.
 *
 * <ul>
 *   <li>{@link Type#UPSERT}: the branch stores this version of the pass ({@code pass} is set)</li>
 *   <li>{@link Type#DELETE}: the branch removes the pass ({@code pass} is null)</li>
 * </ul>
 *
 * <p>Why one event per pass, keyed by application number: Kafka spreads the keys over the topic's
 * partitions, so several listener pods can deliver at once (one partition per consumer at a time), and
 * Kafka still keeps the order of the events <b>for each pass</b>, because one key always goes to the
 * same partition.
 *
 * <p>{@code seq} comes from one DC sequence and only grows. A branch applies an event only if it is newer
 * than what it has for that pass, so redelivered or out-of-order events (retries, rebalances, many pods)
 * cannot undo a newer change.
 *
 * @param eventId       unique per event, for tracing and logs
 * @param seq           the DC's order of changes: higher = newer
 * @param type          upsert or delete
 * @param branchId      the branch this event is for, e.g. {@code A}; a branch rejects events for another branch
 * @param occurredAt    when the DC created the event (information only; ordering uses {@code seq})
 * @param applicationNo the pass's id, e.g. {@code APP-2026-000123}; also the record key
 * @param pass          the pass (upsert), or null (delete)
 */
public record PassEvent(UUID eventId, long seq, Type type, String branchId, Instant occurredAt,
                        String applicationNo, TravelPass pass) {

    public enum Type { UPSERT, DELETE }

    public PassEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(applicationNo, "applicationNo");
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive");
        }
        if (type == Type.UPSERT && pass == null) {
            throw new IllegalArgumentException("an UPSERT needs the pass");
        }
        if (type == Type.DELETE && pass != null) {
            throw new IllegalArgumentException("a DELETE carries no pass");
        }
    }

    public static PassEvent upsert(long seq, String branchId, Instant at, String applicationNo, TravelPass pass) {
        return new PassEvent(UUID.randomUUID(), seq, Type.UPSERT, branchId, at, applicationNo, pass);
    }

    public static PassEvent delete(long seq, String branchId, Instant at, String applicationNo) {
        return new PassEvent(UUID.randomUUID(), seq, Type.DELETE, branchId, at, applicationNo, null);
    }

    /** Kafka topic of a branch: {@code A -> dc.branch-a}. */
    public static String topicFor(String branchId) {
        return "dc.branch-" + branchId.toLowerCase(Locale.ROOT);
    }

    /** Dead-letter topic of a branch: records its listener could not deliver after retries. */
    public static String deadLetterTopicFor(String branchId) {
        return topicFor(branchId) + ".DLT";
    }
}
