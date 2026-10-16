package com.branchdc.dc.sync;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    public void markPublished(String eventId, Instant at) {
        logs.findById(eventId).ifPresent(l -> l.markPublished(at));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String eventId, String reason) {
        logs.findById(eventId).ifPresent(l -> l.markFailed(reason));
    }
}
