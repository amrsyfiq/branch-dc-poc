package com.branchdc.branch.store;

import com.branchdc.branch.BranchProperties;
import com.branchdc.events.BranchSyncEvent;
import com.branchdc.events.ProductPrice;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * This branch's copy of its data, and the rules for changing it.
 *
 * <p>An event from the DC is applied at most once and never goes backwards in time:
 * <ol>
 *   <li>wrong branch id: rejected ({@link WrongBranchException}), nothing stored</li>
 *   <li>event id already seen: {@code DUPLICATE}, nothing changes (Kafka delivers at least once)</li>
 *   <li>older than the event the data came from: {@code STALE}, recorded but not applied
 *       (e.g. a retried snapshot arriving after a newer purge)</li>
 *   <li>otherwise {@code APPLIED}: a snapshot replaces all products; a purge deletes them</li>
 * </ol>
 */
@Service
public class BranchStore {

    private static final Logger log = LoggerFactory.getLogger(BranchStore.class);

    public enum Result { APPLIED, DUPLICATE, STALE }

    private final BranchProperties branch;
    private final BranchProductRepository products;
    private final AppliedEventRepository events;
    private final EntityManager entityManager;

    public BranchStore(BranchProperties branch, BranchProductRepository products, AppliedEventRepository events,
                       EntityManager entityManager) {
        this.branch = branch;
        this.products = products;
        this.events = events;
        this.entityManager = entityManager;
    }

    @Transactional
    public Result apply(BranchSyncEvent event) {
        if (!branch.id().equalsIgnoreCase(event.branchId())) {
            throw new WrongBranchException(branch.id(), event.branchId());
        }
        // One event at a time: the duplicate and "is it older?" checks below must not interleave
        // with another delivery. Released at commit/rollback.
        entityManager.createNativeQuery("LOCK TABLE applied_event IN EXCLUSIVE MODE").executeUpdate();

        String id = event.eventId().toString();
        if (events.existsById(id)) {
            log.info("Event {} already received; ignored", id);
            return Result.DUPLICATE;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<AppliedEvent> current = events.findFirstByOutcomeOrderByOccurredAtDesc(AppliedEvent.Outcome.APPLIED);
        if (current.isPresent() && event.occurredAt().isBefore(current.get().getOccurredAt())) {
            log.warn("{} {} from {} is older than the current data ({}); ignored",
                    event.type(), id, event.occurredAt(), current.get().getOccurredAt());
            events.save(AppliedEvent.of(event, AppliedEvent.Outcome.STALE, now));
            return Result.STALE;
        }

        products.deleteAllInBatch();
        if (event.type() == BranchSyncEvent.Type.SNAPSHOT) {
            products.saveAll(event.products().stream().map(p -> BranchProduct.from(p, now)).toList());
        }
        events.save(AppliedEvent.of(event, AppliedEvent.Outcome.APPLIED, now));
        log.info("Applied {} {}: branch {} now has {} products", event.type(), id, branch.id(), event.products().size());
        return Result.APPLIED;
    }

    @Transactional(readOnly = true)
    public BranchData read() {
        List<ProductPrice> list = products.findAllByOrderBySkuAsc().stream().map(BranchProduct::toDto).toList();
        LastSync last = events.findFirstByOutcomeOrderByOccurredAtDesc(AppliedEvent.Outcome.APPLIED)
                .map(e -> new LastSync(e.getId(), e.getType(), e.getOccurredAt(), e.getReceivedAt()))
                .orElse(null);
        return new BranchData(branch.id(), list, last);
    }

    /** What {@code GET /data} returns: this branch's products and where they came from. */
    public record BranchData(String site, List<ProductPrice> products, LastSync lastSync) {
    }

    public record LastSync(String eventId, BranchSyncEvent.Type type, Instant occurredAt, Instant receivedAt) {
    }
}
