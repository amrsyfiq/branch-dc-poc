package com.branchdc.branch.web;

import com.branchdc.branch.BranchProperties;
import com.branchdc.branch.store.BranchStore;
import com.branchdc.branch.store.BranchStore.PageView;
import com.branchdc.branch.store.BranchStore.PassView;
import com.branchdc.branch.store.BranchStore.Summary;
import com.branchdc.events.PassEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The branch's HTTP API. Reached only through this branch's WSO2 gateway.
 *
 * <pre>
 * GET  /data?page=0&amp;size=20   this checkpoint's live passes, most recently changed first (read scope)
 * GET  /summary?from=&amp;to=       counts, the newest DC seq applied, and rows with a seq in [from, to] (read scope)
 * POST /passes/search           the counter check: passes for one passport (read scope)
 * POST /events                  apply one event from the DC, delivered by this branch's listener (sync scope)
 * </pre>
 *
 * The counter check is a POST with the passport number in the body, not {@code GET /passes?passport=...}:
 * URLs end up in gateway and proxy access logs, request bodies don't.
 */
@RestController
public class BranchController {

    private final BranchProperties branch;
    private final BranchStore store;

    public BranchController(BranchProperties branch, BranchStore store) {
        this.branch = branch;
        this.store = store;
    }

    @GetMapping("/data")
    public DataResponse data(@RequestParam(defaultValue = "0") @Min(0) int page,
                             @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return new DataResponse(branch.id(), store.page(page, size));
    }

    @GetMapping("/summary")
    public Summary summary(@RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        return store.summary(from, to);
    }

    @PostMapping("/passes/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return new SearchResponse(store.lookup(request.passportNo().trim().toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * 200 for APPLIED, DUPLICATE and STALE alike: in every case the branch has dealt with the event and the
     * listener must not send it again. A wrong branch id is a 422 (see ApiExceptionHandler).
     */
    @PostMapping("/events")
    public EventResult receive(@RequestBody PassEvent event) {
        return new EventResult(event.eventId().toString(), event.applicationNo(), event.seq(), store.apply(event));
    }

    public record DataResponse(String site, PageView<PassView> passes) {
    }

    public record SearchRequest(@NotBlank @Size(max = 20) String passportNo) {
        @Override
        public String toString() {
            return "SearchRequest[passport number hidden]";
        }
    }

    public record SearchResponse(List<PassView> matches) {
    }

    public record EventResult(String eventId, String applicationNo, long seq, BranchStore.Result result) {
    }
}
