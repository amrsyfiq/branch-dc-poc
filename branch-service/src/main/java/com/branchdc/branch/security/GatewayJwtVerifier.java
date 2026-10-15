package com.branchdc.branch.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks the JWT that this branch's WSO2 gateway adds to every request it forwards ({@code X-JWT-Assertion}).
 *
 * <p>The gateway has already checked the caller's OAuth2 token and scope. This JWT is the gateway's signed
 * statement about that call. Verifying it with <b>this branch's</b> WSO2 certificate proves the request came
 * through this branch's gateway: not around it, and not through another branch's gateway (each WSO2 has its
 * own signing key, see infra/wso2/init-keys.mjs).
 */
public class GatewayJwtVerifier {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final RSASSAVerifier signatureVerifier;
    private final String expectedIssuer;
    private final Clock clock;

    public GatewayJwtVerifier(RSAPublicKey gatewayPublicKey, String expectedIssuer, Clock clock) {
        this.signatureVerifier = new RSASSAVerifier(gatewayPublicKey);
        this.expectedIssuer = expectedIssuer;
        this.clock = clock;
    }

    /** @throws InvalidGatewayTokenException if the token must not be trusted */
    public Caller verify(String token) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new InvalidGatewayTokenException("Malformed gateway JWT");
        }

        // Only the algorithm WSO2 is configured to use. Never trust "alg" blindly
        // (classic attacks: "none", or HS256 signed with the public key as the HMAC secret).
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidGatewayTokenException("Unexpected JWT algorithm " + jwt.getHeader().getAlgorithm());
        }
        try {
            if (!jwt.verify(signatureVerifier)) {
                throw new InvalidGatewayTokenException("Gateway JWT signature is invalid (not signed by this branch's gateway)");
            }
        } catch (JOSEException ex) {
            throw new InvalidGatewayTokenException("Gateway JWT signature could not be verified");
        }

        Instant now = clock.instant();
        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.toInstant().plus(CLOCK_SKEW).isBefore(now)) {
            throw new InvalidGatewayTokenException("Gateway JWT has expired");
        }
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new InvalidGatewayTokenException("Unexpected JWT issuer " + claims.getIssuer());
        }
        return new Caller(claims.getSubject(), application(claims), scopes(claims));
    }

    private static Set<String> scopes(JWTClaimsSet claims) {
        Object scope = claims.getClaim("scope");
        if (!(scope instanceof String value) || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.trim().split("\\s+")).collect(Collectors.toSet());
    }

    private static String application(JWTClaimsSet claims) {
        Object name = claims.getClaim("http://wso2.org/claims/applicationname");
        return name instanceof String s ? s : null;
    }

    /** Who called, as stated by the gateway: the WSO2 application (e.g. listener-a) and its scopes. */
    public record Caller(String subject, String application, Set<String> scopes) {
    }
}
