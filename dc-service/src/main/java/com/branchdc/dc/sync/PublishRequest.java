package com.branchdc.dc.sync;

import com.branchdc.events.PassEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one request sends, handed to {@link PassEventPublisher} once the transaction has committed: one event of
 * {@code type} per pass, the i-th pass with the i-th sequence number. Application numbers, not the passes: the
 * publisher loads and sends them a chunk at a time, so memory does not grow with the size of a checkpoint.
 */
record PublishRequest(UUID requestId, SyncLog.Kind kind, String branchCode, PassEvent.Type type, Instant occurredAt,
                      List<String> applicationNos, List<Long> seqs) {

    PublishRequest {
        if (applicationNos.size() != seqs.size()) {
            throw new IllegalArgumentException("one sequence number per pass");
        }
    }

    int size() {
        return applicationNos.size();
    }
}
