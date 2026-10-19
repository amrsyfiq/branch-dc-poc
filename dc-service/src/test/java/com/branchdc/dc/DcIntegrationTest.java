package com.branchdc.dc;

import com.branchdc.dc.sync.SyncLogRepository;
import com.branchdc.dc.sync.SyncService;
import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DC end to end against a real PostgreSQL (Flyway schema + seed) and a real Kafka broker:
 * HTTP request -> sync_log row -> commit -> one record per pass on the branch's topic.
 * The DC waits for Kafka's acknowledgements before answering, so records can be read right away.
 *
 * <p>All tests share one database. Only the rush test adds passes, and only to checkpoint A, so tests that
 * count passes use checkpoint B (16 seeded passes, 1 revoked). Events are sent in chunks of 7 here (1,000 in the
 * service), so every sync, purge and rush below crosses chunk boundaries: B's 16 passes go as 7 + 7 + 2.
 * Logs are JSON here (as in Docker and Kubernetes) and include DEBUG, so the personal-data check sees every line
 * the services can write.
 */
@SpringBootTest(properties = {"logging.structured.format.console=ecs", "logging.level.com.branchdc=DEBUG",
        "dc.publish.chunk-size=7"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class DcIntegrationTest {

    private static final int SEEDED_B = 16;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private SyncService syncService;
    @Autowired
    private SyncLogRepository syncLogs;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dataIsPagedAndFilteredByBranch() throws Exception {
        mvc.perform(get("/data").param("branch", "b").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value("DC"))
                .andExpect(jsonPath("$.branch").value("B"))
                .andExpect(jsonPath("$.passes.total").value(SEEDED_B))
                .andExpect(jsonPath("$.passes.items.length()").value(5))
                .andExpect(jsonPath("$.passes.items[*].checkpoint").value(everyItem(is("B"))))
                // newest application number first
                .andExpect(jsonPath("$.passes.items[0].applicationNo").value("APP-2026-000040"));
        mvc.perform(get("/data").param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get("/data").param("branch", "Z")).andExpect(status().isNotFound());
    }

    @Test
    void summaryCountsEachCheckpointsPasses() throws Exception {
        mvc.perform(get("/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[*].code").value(org.hamcrest.Matchers.contains("A", "B")))
                .andExpect(jsonPath("$.checkpoints[1].kind").value("LAND"))
                .andExpect(jsonPath("$.checkpoints[1].passes").value(SEEDED_B))
                .andExpect(jsonPath("$.checkpoints[1].approved").value(SEEDED_B - 1))
                .andExpect(jsonPath("$.checkpoints[1].revoked").value(1));
    }

    @Test
    void syncPublishesOneUpsertPerPassKeyedByApplicationNumberToThatBranchOnly() throws Exception {
        String body = mvc.perform(post("/sync/B"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.branch").value("B"))
                .andExpect(jsonPath("$.kind").value("SYNC"))
                .andExpect(jsonPath("$.eventCount").value(SEEDED_B))
                .andExpect(jsonPath("$.publishedCount").value(SEEDED_B))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();

        List<ConsumerRecord<String, String>> records = recordsOf(body, "dc.branch-b");
        List<PassEvent> events = records.stream().map(r -> json.readValue(r.value(), PassEvent.class)).toList();
        Set<String> seededB = Set.copyOf(jdbc.queryForList(
                "select application_no from travel_pass where checkpoint_code = 'B'", String.class));

        assertThat(events).hasSize(SEEDED_B).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(PassEvent.Type.UPSERT);
            assertThat(e.branchId()).isEqualTo("B");
            assertThat(e.pass()).isNotNull();
        });
        assertThat(events).extracting(PassEvent::applicationNo).containsExactlyInAnyOrderElementsOf(seededB);
        assertThat(events).extracting(PassEvent::seq).doesNotHaveDuplicates();
        // key = application number, so the passes spread over the partitions (several listeners can deliver)
        assertThat(records).allSatisfy(r -> assertThat(r.key()).isEqualTo(JsonPath.read(r.value(), "$.applicationNo")));
        assertThat(records.stream().map(ConsumerRecord::partition).distinct().count()).isGreaterThan(1);
        // the revoked pass travels too, so the checkpoint shows "revoked" instead of "unknown"
        assertThat(events).filteredOn(e -> e.pass().status() == TravelPass.Status.REVOKED).hasSize(1);
        // every record carries the request's trace id, for following a pass through the logs
        assertThat(records.stream().map(DcIntegrationTest::traceId).distinct().toList()).hasSize(1).first()
                .asString().matches("[0-9a-f]{32}");

        assertThat(recordsOf(body, "dc.branch-a")).as("nothing on branch A's topic").isEmpty();
    }

    @Test
    void purgePublishesOneDeleteWithoutPersonalDataPerPass() throws Exception {
        String body = mvc.perform(post("/purge/b"))   // lower case is accepted
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("PURGE"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();

        List<PassEvent> events = recordsOf(body, "dc.branch-b").stream()
                .map(r -> json.readValue(r.value(), PassEvent.class)).toList();
        assertThat(events).hasSize(SEEDED_B).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(PassEvent.Type.DELETE);
            assertThat(e.pass()).isNull();
        });
    }

    @Test
    void rushCreatesNewPassesForTheBranchAndSendsThemAll() throws Exception {
        int count = 1201; // the rush saves 500 at a time: 500 + 500 + 201
        long before = jdbc.queryForObject("select count(*) from travel_pass where checkpoint_code = 'A'", Long.class);

        String body = mvc.perform(post("/rush/A").param("count", String.valueOf(count)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("RUSH"))
                .andExpect(jsonPath("$.eventCount").value(count))
                .andExpect(jsonPath("$.publishedCount").value(count))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(jdbc.queryForObject("select count(*) from travel_pass where checkpoint_code = 'A'", Long.class))
                .isEqualTo(before + count);
        List<PassEvent> events = recordsOf(body, "dc.branch-a").stream()
                .map(r -> json.readValue(r.value(), PassEvent.class)).toList();
        assertThat(events).hasSize(count).allSatisfy(e -> {
            assertThat(e.applicationNo()).matches("APP-\\d{4}-\\d{6}");
            assertThat(e.pass().status()).isEqualTo(TravelPass.Status.APPROVED);
        });
        assertThat(events).extracting(PassEvent::applicationNo).doesNotHaveDuplicates();

        mvc.perform(post("/rush/A").param("count", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/rush/A").param("count", "20001")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownBranchIs404AndNothingIsLogged() throws Exception {
        long before = syncLogs.count();
        mvc.perform(post("/sync/Z")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Unknown branch: Z"));
        mvc.perform(post("/purge/Z")).andExpect(status().isNotFound());
        mvc.perform(post("/rush/Z")).andExpect(status().isNotFound());
        assertThat(syncLogs.count()).isEqualTo(before);
    }

    @Test
    void rolledBackRequestPublishesNothing() {
        long recordsBefore = TopicReader.endOffsetSum("dc.branch-b");
        long logsBefore = syncLogs.count();

        tx.executeWithoutResult(status -> {
            syncService.requestSync("B");
            status.setRollbackOnly(); // e.g. a later step in the same transaction failed
        });

        assertThat(syncLogs.count()).isEqualTo(logsBefore);
        assertThat(TopicReader.endOffsetSum("dc.branch-b")).isEqualTo(recordsBefore);
    }

    @Test
    void syncLogListsNewestFirst() throws Exception {
        mvc.perform(post("/sync/B")).andExpect(status().isAccepted());
        mvc.perform(post("/purge/B")).andExpect(status().isAccepted());

        mvc.perform(get("/sync-log").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("PURGE"))
                .andExpect(jsonPath("$[1].kind").value("SYNC"));
        mvc.perform(get("/sync-log").param("limit", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void logsAreJsonWithTheTraceIdAndNeverContainPersonalData(CapturedOutput output) throws Exception {
        String body = mvc.perform(post("/sync/B")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/rush/A").param("count", "25")).andExpect(status().isAccepted());
        mvc.perform(get("/data").param("size", "200")).andExpect(status().isOk());

        // The summary line of the sync is a JSON object with the request id, the site and the trace id
        String traceId = traceId(recordsOf(body, "dc.branch-b").getFirst());
        String requestId = JsonPath.read(body, "$.requestId");
        String line = output.getOut().lines().filter(l -> l.contains(requestId) && l.contains("events published"))
                .findFirst().orElseThrow();
        JsonNode logLine = json.readTree(line);
        assertThat(logLine.path("site").asString()).isEqualTo("DC");
        assertThat(logLine.path("requestId").asString()).isEqualTo(requestId);
        assertThat(line).contains(traceId);

        // No passport number or name of any traveller (seeded, or made up by the rushes), anywhere in the output
        List<Map<String, Object>> travellers = jdbc.queryForList("select passport_no, full_name from travel_pass");
        assertThat(travellers).hasSizeGreaterThan(40);
        String everything = output.getAll();
        for (Map<String, Object> t : travellers) {
            assertThat(everything).doesNotContain((String) t.get("passport_no")).doesNotContain((String) t.get("full_name"));
        }
    }

    /** The records of one request on a topic: their seq lies between the request's first and last seq. */
    private static List<ConsumerRecord<String, String>> recordsOf(String responseBody, String topic) {
        Number first = JsonPath.read(responseBody, "$.firstSeq");
        Number last = JsonPath.read(responseBody, "$.lastSeq");
        return TopicReader.readAll(topic).stream().filter(r -> {
            long seq = ((Number) JsonPath.read(r.value(), "$.seq")).longValue();
            return seq >= first.longValue() && seq <= last.longValue();
        }).collect(Collectors.toList());
    }

    /** {@code traceparent: 00-<trace id>-<span id>-01} (W3C Trace Context). */
    private static String traceId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("traceparent");
        assertThat(header).as("traceparent header").isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8).split("-")[1];
    }
}
