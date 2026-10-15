package com.branchdc.dc.web;

import com.branchdc.dc.catalog.CatalogService;
import com.branchdc.dc.catalog.CatalogService.BranchData;
import com.branchdc.dc.sync.SyncService;
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
 * GET  /data             every branch with its products and prices
 * POST /sync/{branch}    send that branch its slice (202 + the log entry)
 * POST /purge/{branch}   tell that branch to delete its data (202 + the log entry)
 * GET  /sync-log         latest sync/purge requests, newest first
 * </pre>
 */
@RestController
public class DcController {

    private final CatalogService catalog;
    private final SyncService sync;

    public DcController(CatalogService catalog, SyncService sync) {
        this.catalog = catalog;
        this.sync = sync;
    }

    @GetMapping("/data")
    public DataResponse data() {
        return new DataResponse("DC", catalog.allData());
    }

    /** 202 Accepted: the event is on its way; the branch applies it asynchronously. */
    @PostMapping("/sync/{branch}")
    public ResponseEntity<SyncLogView> sync(@PathVariable String branch) {
        String eventId = sync.requestSnapshot(normalise(branch));
        return ResponseEntity.accepted().body(sync.find(eventId));
    }

    @PostMapping("/purge/{branch}")
    public ResponseEntity<SyncLogView> purge(@PathVariable String branch) {
        String eventId = sync.requestPurge(normalise(branch));
        return ResponseEntity.accepted().body(sync.find(eventId));
    }

    @GetMapping("/sync-log")
    public List<SyncLogView> syncLog(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return sync.recent(limit);
    }

    /** {@code /sync/a} and {@code /sync/A} mean the same branch. */
    private static String normalise(String branch) {
        return branch.toUpperCase(Locale.ROOT);
    }

    public record DataResponse(String site, List<BranchData> branches) {
    }
}
