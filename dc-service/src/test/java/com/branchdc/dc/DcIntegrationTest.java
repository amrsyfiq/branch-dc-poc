package com.branchdc.dc;

import com.branchdc.dc.sync.SyncLogRepository;
import com.branchdc.dc.sync.SyncService;
import com.branchdc.events.BranchSyncEvent;
import com.branchdc.events.ProductPrice;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DC end to end against real Oracle (Flyway schema + seed) and a real Kafka broker:
 * HTTP request -> sync_log row -> commit -> record on the branch's topic.
 * The DC waits for Kafka's acknowledgement before answering, so records can be read right away.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DcIntegrationTest {

    private static final List<String> SEED_SKUS_A = List.of("P-001", "P-002", "P-003", "P-004", "P-005", "P-006", "P-009");
    private static final List<String> SEED_SKUS_B = List.of("P-001", "P-002", "P-003", "P-007", "P-008", "P-010");

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

    @Test
    void dataReturnsEveryBranchWithItsOwnProducts() throws Exception {
        mvc.perform(get("/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.site").value("DC"))
                .andExpect(jsonPath("$.branches[*].code").value(org.hamcrest.Matchers.contains("A", "B")))
                .andExpect(jsonPath("$.branches[0].products[*].sku").value(org.hamcrest.Matchers.contains(SEED_SKUS_A.toArray())))
                .andExpect(jsonPath("$.branches[1].products[*].sku").value(org.hamcrest.Matchers.contains(SEED_SKUS_B.toArray())))
                // the same product has a different price per branch
                .andExpect(jsonPath("$.branches[0].products[0].price").value(42.50))
                .andExpect(jsonPath("$.branches[1].products[0].price").value(44.00));
    }

    @Test
    void syncPublishesOnlyThatBranchsSliceToItsOwnTopic() throws Exception {
        String body = mvc.perform(post("/sync/A"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.branch").value("A"))
                .andExpect(jsonPath("$.type").value("SNAPSHOT"))
                .andExpect(jsonPath("$.productCount").value(7))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        String eventId = JsonPath.read(body, "$.eventId");

        ConsumerRecord<String, String> record = findRecord("dc.branch-a", eventId);
        assertThat(record).as("event on dc.branch-a").isNotNull();
        assertThat(record.key()).isEqualTo("A");

        BranchSyncEvent event = json.readValue(record.value(), BranchSyncEvent.class);
        assertThat(event.type()).isEqualTo(BranchSyncEvent.Type.SNAPSHOT);
        assertThat(event.branchId()).isEqualTo("A");
        assertThat(event.products()).extracting(ProductPrice::sku).containsExactlyElementsOf(SEED_SKUS_A);
        assertThat(event.products()).first()
                .satisfies(p -> assertThat(p.price()).isEqualByComparingTo(new BigDecimal("42.50")));

        assertThat(findRecord("dc.branch-b", eventId)).as("nothing on branch B's topic").isNull();
    }

    @Test
    void purgePublishesAPurgeEventWithoutProducts() throws Exception {
        String body = mvc.perform(post("/purge/b"))   // lower case is accepted
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.branch").value("B"))
                .andExpect(jsonPath("$.type").value("PURGE"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        String eventId = JsonPath.read(body, "$.eventId");

        BranchSyncEvent event = json.readValue(findRecord("dc.branch-b", eventId).value(), BranchSyncEvent.class);
        assertThat(event.type()).isEqualTo(BranchSyncEvent.Type.PURGE);
        assertThat(event.products()).isEmpty();
        assertThat(findRecord("dc.branch-a", eventId)).isNull();
    }

    @Test
    void unknownBranchIs404AndNothingIsLogged() throws Exception {
        long before = syncLogs.count();
        mvc.perform(post("/sync/Z")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Unknown branch: Z"));
        mvc.perform(post("/purge/Z")).andExpect(status().isNotFound());
        assertThat(syncLogs.count()).isEqualTo(before);
    }

    @Test
    void rolledBackRequestPublishesNothing() throws Exception {
        AtomicReference<String> eventId = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            eventId.set(syncService.requestSnapshot("A"));
            status.setRollbackOnly(); // e.g. a later step in the same transaction failed
        });

        assertThat(syncLogs.findById(eventId.get())).isEmpty();
        assertThat(findRecord("dc.branch-a", eventId.get())).isNull();
    }

    @Test
    void syncLogListsNewestFirst() throws Exception {
        mvc.perform(post("/sync/B")).andExpect(status().isAccepted());
        mvc.perform(post("/purge/A")).andExpect(status().isAccepted());

        mvc.perform(get("/sync-log").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].branch").value("A"))
                .andExpect(jsonPath("$[0].type").value("PURGE"))
                .andExpect(jsonPath("$[1].branch").value("B"))
                .andExpect(jsonPath("$[1].type").value("SNAPSHOT"));
        mvc.perform(get("/sync-log").param("limit", "0")).andExpect(status().isBadRequest());
    }

    /** Reads the whole topic from the beginning and returns the record with this event id, or null. */
    private ConsumerRecord<String, String> findRecord(String topic, String eventId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records.stream()
                    .filter(r -> eventId.equals(JsonPath.read(r.value(), "$.eventId")))
                    .findFirst().orElse(null);
        }
    }
}
