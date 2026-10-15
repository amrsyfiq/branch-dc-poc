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
 * @param branchId         the branch this listener serves, e.g. {@code A}: it reads {@code dc.branch-a} only
 * @param branchUrl        where the branch's API is: its WSO2 gateway, e.g. https://wso2-a:8244/branch-a/1.0.0
 * @param timeout          connect and read timeout for one call
 * @param retry            how often a failed delivery is retried before the record goes to the dead-letter topic
 * @param auth             OAuth2 client credentials for the branch's WSO2 (no token is sent if tokenUrl is empty)
 * @param trustCertificate the only certificate trusted for HTTPS (the branch's WSO2), e.g. {@code file:/certs/gateway.pem};
 *                         empty = the JDK defaults
 */
@Validated
@ConfigurationProperties("listener")
public record ListenerProperties(@NotBlank String branchId, @NotNull URI branchUrl, @NotNull Duration timeout,
                                 @Valid @NotNull Retry retry, @Valid @NotNull Auth auth, String trustCertificate) {

    /** 1 attempt + maxRetries retries, waiting initialInterval, then x multiplier each time, up to maxInterval. */
    public record Retry(@Min(0) int maxRetries, @NotNull Duration initialInterval, double multiplier,
                        @NotNull Duration maxInterval) {
    }

    /**
     * Client-credentials grant against the branch's WSO2 token endpoint.
     *
     * @param scope e.g. {@code branch_a:sync}; WSO2 grants it only to applications whose owner has the bound role
     */
    public record Auth(URI tokenUrl, String clientId, String clientSecret, String scope) {

        public boolean enabled() {
            return tokenUrl != null && !tokenUrl.toString().isBlank();
        }
    }

    public String topic() {
        return BranchSyncEvent.topicFor(branchId);
    }

    public String deadLetterTopic() {
        return BranchSyncEvent.deadLetterTopicFor(branchId);
    }
}
