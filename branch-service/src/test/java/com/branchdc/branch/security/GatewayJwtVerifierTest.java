package com.branchdc.branch.security;

import com.branchdc.branch.security.GatewayJwtVerifier.Caller;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which gateway JWTs a branch accepts. "wso2A" stands in for this branch's WSO2, "wso2B" for the other one. */
class GatewayJwtVerifierTest {

    private static final KeyPair WSO2_A = TestJwts.newKeyPair();
    private static final KeyPair WSO2_B = TestJwts.newKeyPair();
    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    private final GatewayJwtVerifier verifier = new GatewayJwtVerifier(
            (RSAPublicKey) WSO2_A.getPublic(), TestJwts.ISSUER, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAJwtSignedByThisBranchsGateway() {
        Caller caller = verifier.verify(TestJwts.rs256(WSO2_A, TestJwts.claims("branch_a:sync other", NOW.plusSeconds(300)).build()));

        assertThat(caller.scopes()).containsExactlyInAnyOrder("branch_a:sync", "other");
        assertThat(caller.application()).isEqualTo("listener-a");
    }

    @Test
    void rejectsAJwtSignedByTheOtherBranchsGateway() {
        String fromB = TestJwts.rs256(WSO2_B, TestJwts.claims("branch_a:sync", NOW.plusSeconds(300)).build());

        assertThatThrownBy(() -> verifier.verify(fromB))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("signature is invalid");
    }

    @Test
    void rejectsUnsignedAndAlgorithmConfusionTokens() {
        var claims = TestJwts.claims("branch_a:sync", NOW.plusSeconds(300)).build();

        assertThatThrownBy(() -> verifier.verify(TestJwts.unsigned(claims)))
                .isInstanceOf(InvalidGatewayTokenException.class);
        assertThatThrownBy(() -> verifier.verify(TestJwts.hs256WithPublicKey(WSO2_A, claims)))
                .isInstanceOf(InvalidGatewayTokenException.class).hasMessageContaining("Unexpected JWT algorithm HS256");
    }

    @Test
    void rejectsExpiredTokensButAllowsSmallClockSkew() {
        String expired = TestJwts.rs256(WSO2_A, TestJwts.claims("branch_a:sync", NOW.minusSeconds(31)).build());
        String withinSkew = TestJwts.rs256(WSO2_A, TestJwts.claims("branch_a:sync", NOW.minusSeconds(29)).build());

        assertThatThrownBy(() -> verifier.verify(expired)).hasMessageContaining("expired");
        assertThat(verifier.verify(withinSkew).scopes()).contains("branch_a:sync");
    }

    @Test
    void rejectsAnotherIssuerAndGarbage() {
        String otherIssuer = TestJwts.rs256(WSO2_A, TestJwts.claims("branch_a:sync", NOW.plusSeconds(300)).issuer("evil").build());

        assertThatThrownBy(() -> verifier.verify(otherIssuer)).hasMessageContaining("Unexpected JWT issuer evil");
        assertThatThrownBy(() -> verifier.verify("not.a.jwt")).isInstanceOf(InvalidGatewayTokenException.class);
    }
}
