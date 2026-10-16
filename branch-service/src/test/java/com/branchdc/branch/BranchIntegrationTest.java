package com.branchdc.branch;

import com.branchdc.branch.store.BranchStore;
import com.branchdc.events.BranchSyncEvent;
import com.branchdc.events.ProductPrice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch A against a real Oracle: how it applies (or refuses) events from the DC.
 * All tests share one database, so each one starts by applying its own snapshot, and event times are
 * "now" or earlier (never in the future) so one test cannot make another's events look stale.
 */
@SpringBootTest(properties = "branch.id=A")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BranchIntegrationTest {

    private static final List<ProductPrice> TWO_PRODUCTS = List.of(
            new ProductPrice("P-001", "Coffee", new BigDecimal("42.50"), "MYR"),
            new ProductPrice("P-002", "Rice", new BigDecimal("27.90"), "MYR"));
    private static final List<ProductPrice> ONE_PRODUCT = List.of(
            new ProductPrice("P-009", "Sambal", new BigDecimal("6.50"), "MYR"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private BranchStore store;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void snapshotReplacesTheBranchsProducts() throws Exception {
        send(snapshot(TWO_PRODUCTS, now())).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("APPLIED"));
        BranchSyncEvent second = snapshot(ONE_PRODUCT, now());
        send(second).andExpect(jsonPath("$.result").value("APPLIED"));

        mvc.perform(get("/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value("A"))
                .andExpect(jsonPath("$.products[*].sku").value(contains("P-009")))
                .andExpect(jsonPath("$.products[0].price").value(6.50))
                .andExpect(jsonPath("$.lastSync.eventId").value(second.eventId().toString()))
                .andExpect(jsonPath("$.lastSync.type").value("SNAPSHOT"));
    }

    @Test
    void purgeDeletesEverything() throws Exception {
        send(snapshot(TWO_PRODUCTS, now())).andExpect(jsonPath("$.result").value("APPLIED"));
        send(BranchSyncEvent.purge("A", now())).andExpect(jsonPath("$.result").value("APPLIED"));

        mvc.perform(get("/data"))
                .andExpect(jsonPath("$.products").isEmpty())
                .andExpect(jsonPath("$.lastSync.type").value("PURGE"));
    }

    @Test
    void redeliveredEventIsAppliedOnlyOnce() throws Exception {
        BranchSyncEvent event = snapshot(TWO_PRODUCTS, now());
        send(event).andExpect(jsonPath("$.result").value("APPLIED"));
        send(BranchSyncEvent.purge("A", now())).andExpect(jsonPath("$.result").value("APPLIED"));

        // Kafka delivers "at least once": the old snapshot arrives again. It must not bring the data back.
        send(event).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));
        mvc.perform(get("/data")).andExpect(jsonPath("$.products").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from applied_event where event_id = ?", Integer.class,
                event.eventId().toString())).isEqualTo(1);
    }

    @Test
    void olderEventDoesNotOverwriteNewerData() throws Exception {
        Instant now = now();
        send(snapshot(ONE_PRODUCT, now)).andExpect(jsonPath("$.result").value("APPLIED"));

        // e.g. a retried snapshot from an hour ago arrives after the current one
        BranchSyncEvent older = snapshot(TWO_PRODUCTS, now.minus(1, ChronoUnit.HOURS));
        send(older).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("STALE"));
        mvc.perform(get("/data")).andExpect(jsonPath("$.products[*].sku").value(contains("P-009")));

        // and it is remembered: the same old event again is a duplicate, not re-evaluated
        send(older).andExpect(jsonPath("$.result").value("DUPLICATE"));
    }

    @Test
    void eventForAnotherBranchIsRejectedAndNothingChanges() throws Exception {
        send(snapshot(ONE_PRODUCT, now())).andExpect(jsonPath("$.result").value("APPLIED"));

        BranchSyncEvent forB = BranchSyncEvent.snapshot("B", now(), TWO_PRODUCTS);
        send(forB).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("This is branch A; refusing an event for branch B"));

        mvc.perform(get("/data")).andExpect(jsonPath("$.products[*].sku").value(contains("P-009")));
        assertThat(jdbc.queryForObject("select count(*) from applied_event where event_id = ?", Integer.class,
                forB.eventId().toString())).isZero();
    }

    @Test
    void incompleteOrUnreadableEventIs400() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SNAPSHOT\",\"branchId\":\"A\",\"products\":[]}"))   // no eventId / occurredAt
                .andExpect(status().isBadRequest());
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameEventDeliveredTwiceAtOnceIsAppliedOnce() throws Exception {
        BranchSyncEvent event = snapshot(TWO_PRODUCTS, now());
        Callable<BranchStore.Result> apply = () -> store.apply(event);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<BranchStore.Result> first = pool.submit(apply);
            Future<BranchStore.Result> second = pool.submit(apply);
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(BranchStore.Result.APPLIED, BranchStore.Result.DUPLICATE);
        }
    }

    private ResultActions send(BranchSyncEvent event) throws Exception {
        return mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(event)));
    }

    private static BranchSyncEvent snapshot(List<ProductPrice> products, Instant at) {
        return new BranchSyncEvent(UUID.randomUUID(), BranchSyncEvent.Type.SNAPSHOT, "A", at, products);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
