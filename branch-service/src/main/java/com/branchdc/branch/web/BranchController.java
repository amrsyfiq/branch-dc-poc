package com.branchdc.branch.web;

import com.branchdc.branch.store.BranchStore;
import com.branchdc.branch.store.BranchStore.BranchData;
import com.branchdc.events.BranchSyncEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The branch's HTTP API. Reached only through this branch's WSO2 gateway (from Phase 3).
 *
 * <pre>
 * GET  /data     this branch's products (read scope)
 * POST /events   apply a snapshot or purge event from the DC, delivered by this branch's listener (sync scope)
 * </pre>
 */
@RestController
public class BranchController {

    private final BranchStore store;

    public BranchController(BranchStore store) {
        this.store = store;
    }

    @GetMapping("/data")
    public BranchData data() {
        return store.read();
    }

    /**
     * 200 for APPLIED, DUPLICATE and STALE alike: in every case the branch has dealt with the event and
     * the listener must not send it again. A wrong branch id is a 422 (see ApiExceptionHandler).
     */
    @PostMapping("/events")
    public EventResult receive(@RequestBody BranchSyncEvent event) {
        return new EventResult(event.eventId().toString(), store.apply(event));
    }

    public record EventResult(String eventId, BranchStore.Result result) {
    }
}
