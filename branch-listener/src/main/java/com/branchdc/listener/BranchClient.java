package com.branchdc.listener;

import com.branchdc.events.BranchSyncEvent;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

/**
 * Delivers one event to the branch: {@code POST {branchUrl}/events}.
 *
 * <p>How the answer is classified decides what the error handler does with the record:
 * <ul>
 *   <li>2xx: done (APPLIED, DUPLICATE or STALE are all fine)</li>
 *   <li>5xx, 408, 429, or no connection: {@link BranchUnavailableException}, retried</li>
 *   <li>any other 4xx: {@link BranchRejectedException}, not retried, straight to the dead-letter topic</li>
 * </ul>
 */
@Component
public class BranchClient {

    private final RestClient http;

    public BranchClient(ListenerProperties props) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().baseUrl(props.branchUrl().toString()).requestFactory(requestFactory).build();
    }

    public void deliver(BranchSyncEvent event) {
        try {
            http.post().uri("/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (response.getStatusCode().is5xxServerError() || status == 408 || status == 429) {
                            throw new BranchUnavailableException("Branch answered " + status + ": " + body);
                        }
                        throw new BranchRejectedException(status, body);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            // e.g. "java.net.ConnectException" (connection refused often has no message of its own)
            throw new BranchUnavailableException("Branch not reachable: "
                    + org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e), e);
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

    /** The branch refused the event (e.g. wrong branch, invalid data); retrying cannot help. */
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
