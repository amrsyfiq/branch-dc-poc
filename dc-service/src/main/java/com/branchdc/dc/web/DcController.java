package com.branchdc.dc.web;

import com.branchdc.dc.pass.PassService;
import com.branchdc.dc.pass.PassService.CheckpointCounts;
import com.branchdc.dc.pass.PassService.PageView;
import com.branchdc.dc.pass.PassService.PassView;
import com.branchdc.dc.sync.SyncService;
import com.branchdc.dc.sync.SyncService.BranchProgress;
import com.branchdc.dc.sync.SyncService.SyncLogView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * The DC's HTTP API.
 *
 * <pre>
 * GET  /data?branch=A&amp;page=0&amp;size=20   passes, newest first: all branches, or one (paged: after a rush there are thousands)
 * GET  /summary                       per checkpoint: pass counts, latest request, newest sequence number published
 * POST /sync/{branch}                 send that branch its whole slice, one upsert per pass (202 + the log entry)
 * POST /purge/{branch}                tell that branch to delete every pass of its slice (202 + the log entry)
 * POST /rush/{branch}?count=1000      holiday rush: create that many new passes and send them (202 + the log entry)
 * GET  /sync-log?limit=20             latest requests, newest first
 * </pre>
 */
@RestController
public class DcController {

    private final PassService passes;
    private final SyncService sync;

    public DcController(PassService passes, SyncService sync) {
        this.passes = passes;
        this.sync = sync;
    }

    @GetMapping("/data")
    public DataResponse data(@RequestParam(required = false) String branch,
                             @RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        String code = branch == null ? null : normalise(branch);
        return new DataResponse("DC", code, passes.page(code, page, size));
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        List<CheckpointSummary> checkpoints = passes.counts().stream()
                .map(c -> CheckpointSummary.of(c, sync.progress(c.code()))).toList();
        return new SummaryResponse("DC", checkpoints);
    }

    /** 202 Accepted: the events are on their way; the branch applies them asynchronously. */
    @PostMapping("/sync/{branch}")
    public ResponseEntity<SyncLogView> sync(@PathVariable String branch) {
        return ResponseEntity.accepted().body(sync.find(sync.requestSync(normalise(branch))));
    }

    @PostMapping("/purge/{branch}")
    public ResponseEntity<SyncLogView> purge(@PathVariable String branch) {
        return ResponseEntity.accepted().body(sync.find(sync.requestPurge(normalise(branch))));
    }

    @PostMapping("/rush/{branch}")
    public ResponseEntity<SyncLogView> rush(@PathVariable String branch,
                                            @RequestParam(defaultValue = "1000") @Min(1) @Max(20000) int count) {
        return ResponseEntity.accepted().body(sync.find(sync.requestRush(normalise(branch), count)));
    }

    @GetMapping("/sync-log")
    public List<SyncLogView> syncLog(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return sync.recent(limit);
    }

    /** {@code /sync/a} and {@code /sync/A} mean the same branch. */
    private static String normalise(String branch) {
        return branch.toUpperCase(Locale.ROOT);
    }

    public record DataResponse(String site, String branch, PageView<PassView> passes) {
    }

    public record SummaryResponse(String site, List<CheckpointSummary> checkpoints) {
    }

    public record CheckpointSummary(String code, String name, String kind, long passes, long approved, long revoked,
                                    Long lastPublishedSeq, SyncLogView lastRequest) {
        static CheckpointSummary of(CheckpointCounts c, BranchProgress p) {
            return new CheckpointSummary(c.code(), c.name(), c.kind().name(), c.passes(), c.approved(), c.revoked(),
                    p.lastPublishedSeq(), p.lastRequest());
        }
    }
}
