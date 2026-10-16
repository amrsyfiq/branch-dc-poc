package com.branchdc.branch.security;

import com.branchdc.branch.security.GatewayJwtVerifier.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Lets a request in only with a valid {@code X-JWT-Assertion} from this branch's WSO2 gateway, and only
 * with the scope the endpoint needs (checked again here, in case the gateway is ever misconfigured):
 *
 * <pre>
 * GET  /data    branch_x:read     (the dashboard)
 * POST /events  branch_x:sync     (the DC's listener for this branch)
 * </pre>
 *
 * 401 without a valid JWT, 403 with a valid one but the wrong scope. {@code /actuator/health} stays open
 * for the container health check (it is not exposed through the gateway).
 */
public class GatewayJwtFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-JWT-Assertion";

    private static final Logger log = LoggerFactory.getLogger(GatewayJwtFilter.class);

    private final GatewayJwtVerifier verifier;
    private final String readScope;
    private final String syncScope;
    private final JsonMapper json;

    public GatewayJwtFilter(GatewayJwtVerifier verifier, String branchId, JsonMapper json) {
        this.verifier = verifier;
        String prefix = "branch_" + branchId.toLowerCase(Locale.ROOT);
        this.readScope = prefix + ":read";
        this.syncScope = prefix + ":sync";
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "Missing " + HEADER + ": call this branch through its WSO2 gateway");
            return;
        }
        Caller caller;
        try {
            caller = verifier.verify(token);
        } catch (InvalidGatewayTokenException ex) {
            reject(request, response, HttpStatus.UNAUTHORIZED, ex.getMessage());
            return;
        }
        String needed = requiredScope(request);
        if (needed != null && !caller.scopes().contains(needed)) {
            reject(request, response, HttpStatus.FORBIDDEN, "Scope " + needed + " required; caller "
                    + caller.application() + " has " + caller.scopes());
            return;
        }
        log.debug("{} {} by {} (scopes {})", request.getMethod(), request.getRequestURI(), caller.application(), caller.scopes());
        chain.doFilter(request, response);
    }

    private String requiredScope(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && path.equals("/data")) {
            return readScope;
        }
        if ("POST".equals(request.getMethod()) && path.equals("/events")) {
            return syncScope;
        }
        return null; // anything else: a valid gateway JWT is enough (and it will be a 404/405 anyway)
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        log.warn("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
