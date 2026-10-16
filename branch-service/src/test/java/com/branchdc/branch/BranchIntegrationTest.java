package com.branchdc.branch;

import com.branchdc.branch.store.BranchStore;
import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch A against a real PostgreSQL: how it applies (or refuses) events from the DC, and the counter check.
 * All tests share one database, so each one uses its own application and passport numbers. Logs are JSON
 * and include DEBUG, so the personal-data check sees every line the branch can write.
 */
@SpringBootTest(properties = {"branch.id=A", "branch.security.mode=none",
        "logging.structured.format.console=ecs", "logging.level.com.branchdc=DEBUG"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class BranchIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur"));
    private static final AtomicLong SEQ = new AtomicLong(1_000_000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private BranchStore store;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void upsertIsStoredAndFoundAtTheCounter() throws Exception {
        String app = newApplicationNo();
        TravelPass pass = pass(newPassport(), TravelPass.Status.APPROVED);
        send(upsert(app, nextSeq(), pass)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED")).andExpect(jsonPath("$.applicationNo").value(app));

        search(pass.passportNo().toLowerCase())   // the officer may type it in lower case
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(1))
                .andExpect(jsonPath("$.matches[0].applicationNo").value(app))
                .andExpect(jsonPath("$.matches[0].fullName").value(pass.fullName()))
                .andExpect(jsonPath("$.matches[0].validToday").value(true));
        search("Z00000000").andExpect(jsonPath("$.matches").isEmpty());
        search("").andExpect(status().isBadRequest());
    }

    @Test
    void revokedPassIsShownButNotValid() throws Exception {
        TravelPass revoked = pass(newPassport(), TravelPass.Status.REVOKED);
        send(upsert(newApplicationNo(), nextSeq(), revoked)).andExpect(jsonPath("$.result").value("APPLIED"));

        search(revoked.passportNo())
                .andExpect(jsonPath("$.matches[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.matches[0].validToday").value(false));
    }

    @Test
    void redeliveredEventIsADuplicateAndChangesNothing() throws Exception {
        PassEvent event = upsert(newApplicationNo(), nextSeq(), pass(newPassport(), TravelPass.Status.APPROVED));
        send(event).andExpect(jsonPath("$.result").value("APPLIED"));
        Map<String, Object> before = row(event.applicationNo());

        // Kafka delivers "at least once": the same event arrives again
        send(event).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));
        assertThat(row(event.applicationNo())).isEqualTo(before);
    }

    @Test
    void olderEventDoesNotOverwriteNewerData() throws Exception {
        String app = newApplicationNo();
        long older = nextSeq();
        long newer = nextSeq();
        TravelPass current = pass(newPassport(), TravelPass.Status.REVOKED);
        send(upsert(app, newer, current)).andExpect(jsonPath("$.result").value("APPLIED"));

        // e.g. a retried event arriving after a newer one for the same pass (another partition owner, a rebalance)
        send(upsert(app, older, pass(newPassport(), TravelPass.Status.APPROVED)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("STALE"));
        assertThat(row(app)).containsEntry("passport_no", current.passportNo()).containsEntry("event_seq", newer);
    }

    @Test
    void deleteLeavesATombstoneThatALateUpsertCannotUndo() throws Exception {
        String app = newApplicationNo();
        long first = nextSeq();
        long delete = nextSeq();
        TravelPass pass = pass(newPassport(), TravelPass.Status.APPROVED);
        send(upsert(app, first, pass)).andExpect(jsonPath("$.result").value("APPLIED"));
        send(PassEvent.delete(delete, "A", Instant.now(), app)).andExpect(jsonPath("$.result").value("APPLIED"));

        search(pass.passportNo()).andExpect(jsonPath("$.matches").isEmpty());
        assertThat(row(app)).containsEntry("deleted", true).containsEntry("passport_no", null).containsEntry("full_name", null);

        // the upsert from before the delete arrives late: it must not bring the pass back
        send(upsert(app, first, pass)).andExpect(jsonPath("$.result").value("STALE"));
        search(pass.passportNo()).andExpect(jsonPath("$.matches").isEmpty());

        // a newer upsert does (the DC synced the pass again)
        send(upsert(app, nextSeq(), pass)).andExpect(jsonPath("$.result").value("APPLIED"));
        search(pass.passportNo()).andExpect(jsonPath("$.matches.length()").value(1));
    }

    @Test
    void eventForAnotherBranchIsRejectedAndNothingIsStored() throws Exception {
        String app = newApplicationNo();
        PassEvent forB = PassEvent.upsert(nextSeq(), "B", Instant.now(), app, pass(newPassport(), TravelPass.Status.APPROVED));
        send(forB).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("This is branch A; refusing an event for branch B"));
        assertThat(jdbc.queryForObject("select count(*) from branch_pass where application_no = ?", Integer.class, app)).isZero();
    }

    @Test
    void incompleteOrUnreadableEventIs400() throws Exception {
        String noPass = """
                {"eventId":"%s","seq":5,"type":"UPSERT","branchId":"A","occurredAt":"2026-10-16T10:00:00Z","applicationNo":"APP-X"}
                """.formatted(UUID.randomUUID());
        String noSeq = """
                {"eventId":"%s","type":"DELETE","branchId":"A","occurredAt":"2026-10-16T10:00:00Z","applicationNo":"APP-X"}
                """.formatted(UUID.randomUUID());
        for (String body : List.of(noPass, noSeq, "not json")) {
            mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    /**
     * Many pods at once: 40 passes, 5 versions each (some deletes), every event delivered twice, all shuffled
     * and applied by 16 threads together. Whatever the order, each pass must end as its newest version.
     */
    @Test
    void concurrentOutOfOrderAndDuplicateDeliveriesEndInTheNewestState() throws Exception {
        Random random = new Random(42);
        List<PassEvent> all = new ArrayList<>();
        Map<String, PassEvent> newest = new java.util.HashMap<>();
        for (int p = 0; p < 40; p++) {
            String app = newApplicationNo();
            for (int v = 0; v < 5; v++) {
                PassEvent e = random.nextInt(4) == 0
                        ? PassEvent.delete(nextSeq(), "A", Instant.now(), app)
                        : upsert(app, nextSeq(), pass(newPassport(), TravelPass.Status.APPROVED));
                all.add(e);
                all.add(e); // the duplicate
                newest.put(app, e); // seq only grows, so the last one is the newest
            }
        }
        Collections.shuffle(all, random);

        List<BranchStore.Result> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<BranchStore.Result>> futures = all.stream().map(e -> pool.submit(() -> store.apply(e))).toList();
            results = new ArrayList<>();
            for (Future<BranchStore.Result> f : futures) {
                results.add(f.get());
            }
        }

        for (PassEvent expected : newest.values()) {
            Map<String, Object> row = row(expected.applicationNo());
            assertThat(row).as(expected.applicationNo()).containsEntry("event_seq", expected.seq())
                    .containsEntry("deleted", expected.type() == PassEvent.Type.DELETE)
                    .containsEntry("passport_no", expected.pass() == null ? null : expected.pass().passportNo());
        }
        // each distinct event was either applied once or found to be older; every second copy is a duplicate or stale
        Map<BranchStore.Result, Long> counts = results.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(counts.get(BranchStore.Result.APPLIED)).isBetween(40L, 200L);
        assertThat(counts.getOrDefault(BranchStore.Result.DUPLICATE, 0L) + counts.getOrDefault(BranchStore.Result.STALE, 0L))
                .isGreaterThanOrEqualTo(200L);
    }

    @Test
    void summaryCountsLivePassesTombstonesAndProgressThroughARequest() throws Exception {
        String app = newApplicationNo();
        long first = nextSeq();
        send(upsert(app, first, pass(newPassport(), TravelPass.Status.APPROVED)));
        send(upsert(newApplicationNo(), nextSeq(), pass(newPassport(), TravelPass.Status.APPROVED)));
        long seq = nextSeq();
        send(PassEvent.delete(seq, "A", Instant.now(), app));

        mvc.perform(get("/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value("A"))
                .andExpect(jsonPath("$.tombstones").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.maxSeq").value(org.hamcrest.Matchers.greaterThanOrEqualTo(seq), Long.class))
                .andExpect(jsonPath("$.inRange").value(org.hamcrest.Matchers.nullValue()));
        // as if one DC request had sent seqs first..seq+1: both passes hold a seq in that range (the first one
        // now holds the delete's), so the branch has applied 2 of its events
        mvc.perform(get("/summary").param("from", String.valueOf(first)).param("to", String.valueOf(seq + 1)))
                .andExpect(jsonPath("$.inRange").value(2));
        mvc.perform(get("/data").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passes.items.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(3)));
    }

    @Test
    void logsNeverContainPersonalData(CapturedOutput output) throws Exception {
        TravelPass pass = pass(newPassport(), TravelPass.Status.APPROVED);
        String app = newApplicationNo();
        send(upsert(app, nextSeq(), pass)).andExpect(jsonPath("$.result").value("APPLIED"));
        send(upsert(app, 1, pass)).andExpect(jsonPath("$.result").value("STALE"));
        search(pass.passportNo()).andExpect(jsonPath("$.matches.length()").value(1));
        mvc.perform(get("/data").param("size", "50")).andExpect(status().isOk());
        send(PassEvent.upsert(nextSeq(), "B", Instant.now(), newApplicationNo(), pass)).andExpect(status().isUnprocessableContent());
        // a malformed lookup: the JSON parser's error message would quote the unquoted passport number
        mvc.perform(post("/passes/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"passportNo\": " + pass.passportNo() + "}")).andExpect(status().isBadRequest());

        assertThat(output.getAll()).contains(app)   // the pass is identified by its application number...
                .doesNotContain(pass.passportNo())  // ...never by who the traveller is
                .doesNotContain(pass.fullName())
                .doesNotContain(pass.dateOfBirth().toString());
    }

    // ---- helpers ----

    private ResultActions send(PassEvent event) throws Exception {
        return mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(event)));
    }

    private ResultActions search(String passport) throws Exception {
        return mvc.perform(post("/passes/search").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("passportNo", passport))));
    }

    private Map<String, Object> row(String applicationNo) {
        return jdbc.queryForMap("select passport_no, full_name, deleted, event_seq from branch_pass where application_no = ?",
                applicationNo);
    }

    private static PassEvent upsert(String app, long seq, TravelPass pass) {
        return PassEvent.upsert(seq, "A", Instant.now(), app, pass);
    }

    /** A pass valid from yesterday to tomorrow, for a made-up traveller with a unique name. */
    private static TravelPass pass(String passport, TravelPass.Status status) {
        return new TravelPass(passport, "Traveller " + passport, "SGP", LocalDate.of(1990, 1, 1).plusDays(SEQ.get() % 5000),
                TravelPass.Type.TOURIST, TODAY.minusDays(1), TODAY.plusDays(1), status);
    }

    private static long nextSeq() {
        return SEQ.incrementAndGet();
    }

    private static String newApplicationNo() {
        return "APP-T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String newPassport() {
        return "T" + (10_000_000 + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 90_000_000L));
    }
}
