package com.branchdc.dc.sync;

import com.branchdc.dc.pass.PassService;
import com.branchdc.events.PassEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns "sync branch A", "purge branch A" and "holiday rush at branch A" into one event per pass on
 * branch A's topic.
 *
 * <p>Each request locks the checkpoint row, reads which passes it concerns (application numbers only), takes a
 * sequence number per pass, writes its log row and hands that over to {@link PassEventPublisher}, which sends the
 * events a chunk at a time, only <b>after</b> the transaction commits. So a rolled-back request never reaches a
 * branch. (Known gap, no outbox: if the app dies between commit and the last chunk, the row stays PENDING with the
 * count confirmed so far, and the rest is not sent. Syncing again fixes it: upserts are idempotent.)
 */
@Service
public class SyncService {

    private final PassService passes;
    private final EventSequence sequence;
    private final SyncLogRepository logs;
    private final ApplicationEventPublisher springEvents;
    private final Clock clock;

    public SyncService(PassService passes, EventSequence sequence, SyncLogRepository logs,
                       ApplicationEventPublisher springEvents, Clock clock) {
        this.passes = passes;
        this.sequence = sequence;
        this.logs = logs;
        this.springEvents = springEvents;
        this.clock = clock;
    }

    /** Sends the branch every pass of its slice (an upsert each). Returns the request id. */
    @Transactional
    public UUID requestSync(String branchCode) {
        String code = passes.lockCheckpoint(branchCode).getCode();
        return record(SyncLog.Kind.SYNC, PassEvent.Type.UPSERT, code, passes.applicationNosOf(code));
    }

    /** Tells the branch to delete every pass of its slice (a delete each). Returns the request id. */
    @Transactional
    public UUID requestPurge(String branchCode) {
        String code = passes.lockCheckpoint(branchCode).getCode();
        return record(SyncLog.Kind.PURGE, PassEvent.Type.DELETE, code, passes.applicationNosOf(code));
    }

    /** The holiday rush: {@code count} new passes for the branch, each sent as an upsert. Returns the request id. */
    @Transactional
    public UUID requestRush(String branchCode, int count) {
        String code = passes.lockCheckpoint(branchCode).getCode();
        return record(SyncLog.Kind.RUSH, PassEvent.Type.UPSERT, code,
                passes.createRush(code, count, ThreadLocalRandom.current()));
    }

    @Transactional(readOnly = true)
    public SyncLogView find(UUID requestId) {
        return logs.findById(requestId).map(SyncLogView::of).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<SyncLogView> recent(int limit) {
        return logs.findByOrderByCreatedAtDesc(Limit.of(limit)).stream().map(SyncLogView::of).toList();
    }

    /** The branch's latest request and the newest sequence number published to it. */
    @Transactional(readOnly = true)
    public BranchProgress progress(String branchCode) {
        return new BranchProgress(logs.lastPublishedSeq(branchCode),
                logs.findFirstByBranchCodeOrderByCreatedAtDesc(branchCode).map(SyncLogView::of).orElse(null));
    }

    /** Takes one sequence number per pass, writes the log row and hands the request over: sent after commit. */
    private UUID record(SyncLog.Kind kind, PassEvent.Type type, String code, List<String> applicationNos) {
        List<Long> seqs = sequence.next(applicationNos.size());
        Instant now = now();
        UUID requestId = UUID.randomUUID();
        Long first = seqs.isEmpty() ? null : seqs.getFirst();
        Long last = seqs.isEmpty() ? null : seqs.getLast();
        logs.save(SyncLog.pending(requestId, code, kind, seqs.size(), first, last, now));
        springEvents.publishEvent(new PublishRequest(requestId, kind, code, type, now, applicationNos, seqs));
        return requestId;
    }

    /** PostgreSQL keeps microseconds; truncating keeps the event and the stored row identical. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record BranchProgress(Long lastPublishedSeq, SyncLogView lastRequest) {
    }

    public record SyncLogView(UUID requestId, String branch, SyncLog.Kind kind, int eventCount, Long firstSeq,
                              Long lastSeq, SyncLog.Status status, int publishedCount, String error,
                              Instant createdAt, Instant publishedAt) {
        static SyncLogView of(SyncLog l) {
            return new SyncLogView(l.getRequestId(), l.getBranchCode(), l.getKind(), l.getEventCount(), l.getFirstSeq(),
                    l.getLastSeq(), l.getStatus(), l.getPublishedCount(), l.getError(), l.getCreatedAt(), l.getPublishedAt());
        }
    }
}
