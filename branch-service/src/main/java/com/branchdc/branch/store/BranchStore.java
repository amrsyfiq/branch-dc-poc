package com.branchdc.branch.store;

import com.branchdc.branch.BranchProperties;
import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * This branch's copy of its travel passes, and the rules for changing it.
 *
 * <p>An event is applied at most once and never goes backwards:
 * <ol>
 *   <li>wrong branch id: rejected ({@link WrongBranchException}), nothing stored</li>
 *   <li>the row already holds this event's seq: {@code DUPLICATE} (Kafka delivers at least once)</li>
 *   <li>the row holds a higher seq: {@code STALE}, ignored (e.g. a retried upsert arriving after a newer delete)</li>
 *   <li>otherwise {@code APPLIED}: an upsert stores the pass; a delete leaves a tombstone without personal data</li>
 * </ol>
 * The check and the write are one SQL statement ({@link PassWriter}), so this holds with any number of
 * listener and branch pods delivering at the same time, in any order.
 */
@Service
public class BranchStore {

    private static final Logger log = LoggerFactory.getLogger(BranchStore.class);

    public enum Result { APPLIED, DUPLICATE, STALE }

    private final BranchProperties branch;
    private final PassWriter writer;
    private final BranchPassRepository passes;
    private final Clock clock;

    public BranchStore(BranchProperties branch, PassWriter writer, BranchPassRepository passes, Clock clock) {
        this.branch = branch;
        this.writer = writer;
        this.passes = passes;
        this.clock = clock;
    }

    @Transactional
    public Result apply(PassEvent event) {
        if (!branch.id().equalsIgnoreCase(event.branchId())) {
            throw new WrongBranchException(branch.id(), event.branchId());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Result result;
        if (writer.upsertIfNewer(event, now)) {
            result = Result.APPLIED;
        } else {
            long current = writer.currentSeq(event.applicationNo()).orElse(Long.MIN_VALUE);
            result = current == event.seq() ? Result.DUPLICATE : Result.STALE;
        }
        // One line per event: the application number identifies the pass; never who the traveller is.
        log.atInfo().addKeyValue("applicationNo", event.applicationNo()).addKeyValue("seq", event.seq())
                .addKeyValue("eventType", event.type()).addKeyValue("outcome", result)
                .log("{} {} {} (seq {})", result, event.type(), event.applicationNo(), event.seq());
        return result;
    }

    /** One page of live passes, most recently changed first. */
    @Transactional(readOnly = true)
    public PageView<PassView> page(int page, int size) {
        Page<BranchPass> result = passes.findByDeletedFalse(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "eventSeq")));
        LocalDate today = LocalDate.now(clock);
        return new PageView<>(result.map(p -> PassView.of(p, today)).getContent(), page, size, result.getTotalElements());
    }

    /**
     * Counts, and optionally how many rows hold an event with a seq in {@code [from, to]}: the DC's latest
     * request covers exactly that range, one event per pass, so this is how far the branch has got with it.
     */
    @Transactional(readOnly = true)
    public Summary summary(Long from, Long to) {
        Long inRange = from == null || to == null ? null : passes.countByEventSeqBetween(from, to);
        return new Summary(branch.id(), passes.countByDeletedFalse(),
                passes.countByDeletedFalseAndStatus(TravelPass.Status.APPROVED),
                passes.countByDeletedFalseAndStatus(TravelPass.Status.REVOKED),
                passes.countByDeletedTrue(), passes.maxEventSeq(), passes.lastReceivedAt(), inRange);
    }

    /** The counter check: every live pass for this passport, and whether it lets the traveller in today. */
    @Transactional(readOnly = true)
    public List<PassView> lookup(String passportNo) {
        LocalDate today = LocalDate.now(clock);
        List<PassView> found = passes.findByPassportNoAndDeletedFalseOrderByValidFromDesc(passportNo).stream()
                .map(p -> PassView.of(p, today)).toList();
        log.debug("Counter lookup: {} pass(es) found", found.size()); // no passport number in the log
        return found;
    }

    public record PageView<T>(List<T> items, int page, int size, long total) {
    }

    /**
     * @param passes      live passes
     * @param tombstones  passes deleted by the DC (kept without personal data)
     * @param maxSeq      the newest DC sequence number this branch has applied
     * @param inRange     rows whose seq is in the requested range (null if no range was asked for)
     */
    public record Summary(String site, long passes, long approved, long revoked, long tombstones, Long maxSeq,
                          Instant lastReceivedAt, Long inRange) {
    }

    /** A pass as the checkpoint shows it, with {@code validToday}: may this traveller enter today? */
    public record PassView(String applicationNo, String passportNo, String fullName, String nationality,
                           LocalDate dateOfBirth, TravelPass.Type passType, LocalDate validFrom, LocalDate validUntil,
                           TravelPass.Status status, boolean validToday, long eventSeq, Instant receivedAt) {
        static PassView of(BranchPass p, LocalDate today) {
            TravelPass t = p.toTravelPass();
            return new PassView(p.getApplicationNo(), t.passportNo(), t.fullName(), t.nationality(), t.dateOfBirth(),
                    t.passType(), t.validFrom(), t.validUntil(), t.status(), t.validOn(today), p.getEventSeq(),
                    p.getReceivedAt());
        }
    }
}
