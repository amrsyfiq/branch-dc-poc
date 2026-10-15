package com.branchdc.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One message from the DC to one branch, on that branch's topic ({@code dc.branch-a}, ...).
 *
 * <ul>
 *   <li>{@link Type#SNAPSHOT}: the complete product list for the branch; it replaces what the branch has.</li>
 *   <li>{@link Type#PURGE}: delete all of the branch's products ({@code products} is empty).</li>
 * </ul>
 *
 * @param eventId    unique per event; receivers use it to apply each event only once
 * @param type       snapshot or purge
 * @param branchId   the branch this event is for, e.g. {@code A}; a branch rejects events for another branch
 * @param occurredAt when the DC created the event; receivers ignore events older than the last one applied
 * @param products   the branch's products and prices (empty for a purge)
 */
public record BranchSyncEvent(UUID eventId, Type type, String branchId, Instant occurredAt,
                              List<ProductPrice> products) {

    public enum Type { SNAPSHOT, PURGE }

    public BranchSyncEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        products = products == null ? List.of() : List.copyOf(products);
    }

    public static BranchSyncEvent snapshot(String branchId, Instant at, List<ProductPrice> products) {
        return new BranchSyncEvent(UUID.randomUUID(), Type.SNAPSHOT, branchId, at, products);
    }

    public static BranchSyncEvent purge(String branchId, Instant at) {
        return new BranchSyncEvent(UUID.randomUUID(), Type.PURGE, branchId, at, List.of());
    }

    /** Kafka topic of a branch: {@code A -> dc.branch-a}. */
    public static String topicFor(String branchId) {
        return "dc.branch-" + branchId.toLowerCase(java.util.Locale.ROOT);
    }

    /** Dead-letter topic of a branch: records its listener could not deliver after retries. */
    public static String deadLetterTopicFor(String branchId) {
        return topicFor(branchId) + ".DLT";
    }
}
