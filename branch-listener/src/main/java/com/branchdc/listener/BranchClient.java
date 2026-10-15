package com.branchdc.listener;

import com.branchdc.events.BranchSyncEvent;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * Delivers one event to the branch: {@code POST {branchUrl}/events}, through the branch's WSO2 gateway
 * with a bearer token (see {@link TokenProvider}).
 *
 * <p>How the answer is classified decides what the error handler does with the record:
 * <ul>
 *   <li>2xx: done (APPLIED, DUPLICATE or STALE are all fine)</li>
 *   <li>401: token rejected; it is dropped and the delivery retried with a new token</li>
 *   <li>5xx, 408, 429, or no connection: {@link BranchUnavailableException}, retried</li>
 *   <li>any other 4xx (e.g. 403 missing scope, 422 wrong branch): {@link BranchRejectedException},
 *       not retried, straight to the dead-letter topic</li>
 * </ul>
 */
@Component
public class BranchClient {

    private final RestClient http;
    private final TokenProvider tokens;

    public BranchClient(ListenerProperties props, TokenProvider tokens) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClients.create(props));
        requestFactory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().baseUrl(props.branchUrl().toString()).requestFactory(requestFactory).build();
        this.tokens = tokens;
    }

    public void deliver(BranchSyncEvent event) {
        try {
            http.post().uri("/events")
                    .headers(h -> tokens.currentToken().ifPresent(h::setBearerAuth))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status == 401) {
                            tokens.invalidate();
                            throw new BranchUnavailableException("Gateway rejected the token (401), retrying with a new one: " + body);
                        }
                        if (response.getStatusCode().is5xxServerError() || status == 408 || status == 429) {
                            throw new BranchUnavailableException("Branch answered " + status + ": " + body);
                        }
                        throw new BranchRejectedException(status, body);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            // e.g. "java.net.ConnectException" (connection refused often has no message of its own)
            throw new BranchUnavailableException("Branch not reachable: " + NestedExceptionUtils.getMostSpecificCause(e), e);
        }
    }

    /** The branch could not take the event right now; worth retrying. */
    public static class BranchUnavailableException extends RuntimeException {
        public BranchUnavailableException(String message) {
            super(message);
        }

        public BranchUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The branch (or its gateway) refused the event; retrying cannot help. */
    public static class BranchRejectedException extends RuntimeException {
        private final int status;

        public BranchRejectedException(int status, String body) {
            super("Branch rejected the event with " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
