package com.branchdc.dc.sync;

import com.branchdc.dc.pass.Pass;
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
import java.util.stream.IntStream;

/**
 * Turns "sync branch A", "purge branch A" and "holiday rush at branch A" into one event per pass on
 * branch A's topic.
 *
 * <p>Each request locks the checkpoint row, takes its sequence numbers, writes its log row and hands the
 * events to {@link PassEventPublisher}, which sends them only <b>after</b> the transaction commits. So a
 * rolled-back request never reaches a branch. (Known gap, no outbox: if the app dies between commit and
 * send, the row stays PENDING and nothing is sent. Syncing again fixes it: upserts are idempotent.)
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
        return upsertAll(SyncLog.Kind.SYNC, code, passes.sliceOf(code));
    }

    /** Tells the branch to delete every pass of its slice (a delete each). Returns the request id. */
    @Transactional
    public UUID requestPurge(String branchCode) {
        String code = passes.lockCheckpoint(branchCode).getCode();
        List<Pass> slice = passes.sliceOf(code);
        List<Long> seqs = sequence.next(slice.size());
        Instant now = now();
        List<PassEvent> events = IntStream.range(0, slice.size())
                .mapToObj(i -> PassEvent.delete(seqs.get(i), code, now, slice.get(i).getApplicationNo())).toList();
        return record(SyncLog.Kind.PURGE, code, events, now);
    }

    /** The holiday rush: {@code count} new passes for the branch, each sent as an upsert. Returns the request id. */
    @Transactional
    public UUID requestRush(String branchCode, int count) {
        String code = passes.lockCheckpoint(branchCode).getCode();
        return upsertAll(SyncLog.Kind.RUSH, code, passes.createRush(code, count, ThreadLocalRandom.current()));
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

    private UUID upsertAll(SyncLog.Kind kind, String code, List<Pass> slice) {
        List<Long> seqs = sequence.next(slice.size());
        Instant now = now();
        List<PassEvent> events = IntStream.range(0, slice.size())
                .mapToObj(i -> PassEvent.upsert(seqs.get(i), code, now, slice.get(i).getApplicationNo(),
                        slice.get(i).toTravelPass())).toList();
        return record(kind, code, events, now);
    }

    private UUID record(SyncLog.Kind kind, String code, List<PassEvent> events, Instant now) {
        UUID requestId = UUID.randomUUID();
        Long first = events.isEmpty() ? null : events.getFirst().seq();
        Long last = events.isEmpty() ? null : events.getLast().seq();
        logs.save(SyncLog.pending(requestId, code, kind, events.size(), first, last, now));
        springEvents.publishEvent(new PublishRequest(requestId, kind, code, events)); // sent after commit
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
