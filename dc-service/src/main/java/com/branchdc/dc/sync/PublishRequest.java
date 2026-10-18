package com.branchdc.dc.sync;

import com.branchdc.events.PassEvent;

import java.util.List;
import java.util.UUID;

/** The events of one request, handed to {@link PassEventPublisher} to send once the transaction has committed. */
record PublishRequest(UUID requestId, SyncLog.Kind kind, String branchCode, List<PassEvent> events) {
}
