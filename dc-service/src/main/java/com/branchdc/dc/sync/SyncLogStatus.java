package com.branchdc.dc.sync;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Updates a log row after the send. {@code REQUIRES_NEW} because it runs in an after-commit callback,
 * where the original transaction is finished: without a new transaction the update would not be saved.
 */
@Component
class SyncLogStatus {

    private final SyncLogRepository logs;

    SyncLogStatus(SyncLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProgress(UUID requestId, int published) {
        logs.findById(requestId).ifPresent(l -> l.markProgress(published));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID requestId, int count, Instant at) {
        logs.findById(requestId).ifPresent(l -> l.markPublished(count, at));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID requestId, int published, String reason) {
        logs.findById(requestId).ifPresent(l -> l.markFailed(published, reason));
    }
}
