package com.branchdc.listener;

import com.branchdc.listener.BranchClient.BranchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Access tokens from the branch's WSO2 (OAuth2 client-credentials grant), cached until shortly before
 * they expire. The listener is a machine, not a user, so it authenticates with its own client id/secret.
 */
@Component
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    /** Renew a little early, so a token never expires between fetching it and the gateway checking it. */
    private static final Duration EARLY = Duration.ofSeconds(60);

    private final ListenerProperties.Auth auth;
    private final RestClient http;
    private final Clock clock = Clock.systemUTC();

    private String token;
    private Instant renewAt = Instant.MIN;

    public TokenProvider(ListenerProperties props) {
        this.auth = props.auth();
        if (auth.enabled() && (isBlank(auth.clientId()) || isBlank(auth.clientSecret()))) {
            throw new IllegalStateException("listener.auth.token-url is set but the client id/secret are missing. "
                    + "Run: node infra/wso2/setup.mjs (writes infra/wso2/generated/<branch>/listener.env)");
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClients.create(props));
        requestFactory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** The bearer token to send, or empty when authentication is switched off. */
    public synchronized Optional<String> currentToken() {
        if (!auth.enabled()) {
            return Optional.empty();
        }
        if (token == null || clock.instant().isAfter(renewAt)) {
            fetch();
        }
        return Optional.of(token);
    }

    /** The gateway said 401: the token was revoked or expired early. The next call fetches a new one. */
    public synchronized void invalidate() {
        token = null;
    }

    private void fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", auth.scope());
        TokenResponse response;
        try {
            response = http.post().uri(auth.tokenUrl())
                    .headers(h -> h.setBasicAuth(auth.clientId(), auth.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            // Retryable: WSO2 may be starting or briefly unreachable.
            throw new BranchUnavailableException("Could not get a token from " + auth.tokenUrl() + ": " + e.getMessage(), e);
        }
        if (response == null || response.access_token() == null) {
            throw new BranchUnavailableException("Token endpoint " + auth.tokenUrl() + " returned no access token");
        }
        token = response.access_token();
        renewAt = clock.instant().plusSeconds(Math.max(0, response.expires_in())).minus(EARLY);
        log.info("New access token from {} (scope '{}', valid {}s)", auth.tokenUrl(), response.scope(), response.expires_in());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Field names as in the OAuth2 token response (RFC 6749 §5.1). */
    record TokenResponse(String access_token, long expires_in, String scope) {
    }
}
