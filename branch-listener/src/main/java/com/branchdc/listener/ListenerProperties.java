package com.branchdc.listener;

import com.branchdc.events.BranchSyncEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration of one listener (one per branch).
 *
 * @param branchId  the branch this listener serves, e.g. {@code A}: it reads {@code dc.branch-a} only
 * @param branchUrl where the branch's API is (Phase 2: the branch itself; Phase 3: its WSO2 gateway)
 * @param timeout   connect and read timeout for one call to the branch
 * @param retry     how often a failed delivery is retried before the record goes to the dead-letter topic
 */
@Validated
@ConfigurationProperties("listener")
public record ListenerProperties(@NotBlank String branchId, @NotNull URI branchUrl, @NotNull Duration timeout,
                                 @Valid @NotNull Retry retry) {

    /** 1 attempt + maxRetries retries, waiting initialInterval, then x multiplier each time, up to maxInterval. */
    public record Retry(@Min(0) int maxRetries, @NotNull Duration initialInterval, double multiplier,
                        @NotNull Duration maxInterval) {
    }

    public String topic() {
        return BranchSyncEvent.topicFor(branchId);
    }

    public String deadLetterTopic() {
        return BranchSyncEvent.deadLetterTopicFor(branchId);
    }
}
