package com.branchdc.branch.security;

import com.branchdc.branch.BranchProperties;
import com.branchdc.branch.store.BranchStore;
import com.branchdc.branch.web.BranchController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch A in its real mode ({@code branch.security.mode=wso2}): the filter in front of the controller.
 * "WSO2-A" is a key pair + certificate made for the test; the store is a mock (no database needed).
 */
@WebMvcTest(value = BranchController.class, properties = {"branch.id=A", "branch.security.mode=wso2"})
@Import(GatewaySecurityConfig.class)
@EnableConfigurationProperties(BranchProperties.class)
class GatewayModeWebTest {

    private static final TestCertificates.Gateway GATEWAY_A = TestCertificates.create("wso2-a-test");
    private static final KeyPair WSO2_A = GATEWAY_A.key();
    private static final KeyPair WSO2_B = TestJwts.newKeyPair();

    @DynamicPropertySource
    static void gatewayCertificate(DynamicPropertyRegistry registry) {
        // Branch A trusts only "WSO2-A": the certificate of the key the tests sign with.
        registry.add("branch.security.certificate", () -> GATEWAY_A.certificatePem().toUri().toString());
        registry.add("branch.security.issuer", () -> TestJwts.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private BranchStore store;

    private final String event = """
            {"eventId":"%s","type":"PURGE","branchId":"A","occurredAt":"2026-10-15T10:00:00Z","products":[]}
            """.formatted(UUID.randomUUID());

    @BeforeEach
    void storeAnswers() {
        when(store.read()).thenReturn(new BranchStore.BranchData("A", List.of(), null));
        when(store.apply(any())).thenReturn(BranchStore.Result.APPLIED);
    }

    @Test
    void withoutTheGatewayJwtEverythingIs401() throws Exception {
        mvc.perform(get("/data")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Missing X-JWT-Assertion: call this branch through its WSO2 gateway"));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void listenerScopeMayDeliverEventsButNotRead() throws Exception {
        String sync = jwt(WSO2_A, "branch_a:sync");

        mvc.perform(post("/events").header(GatewayJwtFilter.HEADER, sync).contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("APPLIED"));
        mvc.perform(get("/data").header(GatewayJwtFilter.HEADER, sync)).andExpect(status().isForbidden());
    }

    @Test
    void dashboardScopeMayReadButNotDeliverEvents() throws Exception {
        String read = jwt(WSO2_A, "branch_a:read");

        mvc.perform(get("/data").header(GatewayJwtFilter.HEADER, read)).andExpect(status().isOk());
        mvc.perform(post("/events").header(GatewayJwtFilter.HEADER, read).contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(startsWith("Scope branch_a:sync required")));
    }

    @Test
    void anotherBranchsScopeOrGatewayIsNotEnough() throws Exception {
        // Branch B's scope, correctly signed by WSO2-A: still not allowed into branch A.
        mvc.perform(post("/events").header(GatewayJwtFilter.HEADER, jwt(WSO2_A, "branch_b:sync"))
                .contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isForbidden());
        // The right scope, but signed by WSO2-B.
        mvc.perform(post("/events").header(GatewayJwtFilter.HEADER, jwt(WSO2_B, "branch_a:sync"))
                .contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    private static String jwt(KeyPair key, String scope) {
        return TestJwts.rs256(key, TestJwts.claims(scope, Instant.now().plusSeconds(300)).build());
    }
}
