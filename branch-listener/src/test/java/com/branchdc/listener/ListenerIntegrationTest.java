package com.branchdc.listener;

import com.branchdc.events.BranchSyncEvent;
import com.branchdc.events.ProductPrice;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Listener A against a real Kafka broker and a fake branch (a tiny HTTP server in the test).
 * The fake branch answers per event id with a scripted list of status codes, e.g. 503 then 200,
 * and records every request it receives.
 */
@SpringBootTest(properties = {
        "listener.branch-id=A",
        "listener.retry.max-retries=2",
        "listener.retry.initial-interval=100ms",
        "listener.retry.multiplier=1",
        "listener.retry.max-interval=100ms",
        "listener.auth.client-id=listener-a-id",
        "listener.auth.client-secret=listener-a-secret",
        "listener.auth.scope=branch_a:sync"
})
@Testcontainers(disabledWithoutDocker = true)
class ListenerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern EVENT_ID = Pattern.compile("\"eventId\"\\s*:\\s*\"([^\"]+)\"");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));
    static final FakeBranch BRANCH = new FakeBranch();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // In the real system the DC service creates these.
            admin.createTopics(List.of(new NewTopic("dc.branch-a", 1, (short) 1), new NewTopic("dc.branch-a.DLT", 1, (short) 1)))
                    .all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("listener.branch-url", BRANCH::url);
        registry.add("listener.auth.token-url", () -> BRANCH.url() + "/token");
    }

    @AfterAll
    static void stopBranch() {
        BRANCH.stop();
    }

    @Autowired
    private JsonMapper json;

    @Test
    void deliversTheEventToItsBranch() {
        BranchSyncEvent event = event("A");
        publish(json.writeValueAsString(event));

        await().atMost(TIMEOUT).until(() -> BRANCH.received(event).size() == 1);
        BranchSyncEvent delivered = json.readValue(BRANCH.received(event).getFirst(), BranchSyncEvent.class);
        assertThat(delivered).isEqualTo(event);
        assertThat(BRANCH.paths).contains("/events");
        // with a bearer token from the token endpoint, obtained with the client credentials and scope
        assertThat(BRANCH.authorizations(event).getFirst()).startsWith("Bearer token-");
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("listener-a-id:listener-a-secret".getBytes());
        assertThat(BRANCH.tokenRequests).first().asString()
                .startsWith(basic).contains("grant_type=client_credentials").contains("scope=branch_a%3Async");
    }

    @Test
    void tokenRejectedByTheGatewayIsReplacedAndTheDeliveryRetried() {
        BranchSyncEvent event = event("A");
        BRANCH.script(event, 401, 200);
        publish(json.writeValueAsString(event));

        await().atMost(TIMEOUT).until(() -> BRANCH.received(event).size() == 2);
        List<String> auth = BRANCH.authorizations(event);
        assertThat(auth.get(1)).startsWith("Bearer token-").isNotEqualTo(auth.get(0));   // a new token
        assertThat(findOnDeadLetterTopic(event.eventId().toString())).isNull();
    }

    @Test
    void cachedTokenIsReusedForLaterDeliveries() {
        BranchSyncEvent first = event("A");
        BranchSyncEvent second = event("A");
        publish(json.writeValueAsString(first));
        await().atMost(TIMEOUT).until(() -> BRANCH.received(first).size() == 1);
        int tokensBefore = BRANCH.tokenRequests.size();

        publish(json.writeValueAsString(second));
        await().atMost(TIMEOUT).until(() -> BRANCH.received(second).size() == 1);
        assertThat(BRANCH.tokenRequests).hasSize(tokensBefore);
        assertThat(BRANCH.authorizations(second)).isEqualTo(BRANCH.authorizations(first));
    }

    @Test
    void branchBrieflyDownIsRetriedAndThenDelivered() {
        BranchSyncEvent event = event("A");
        BRANCH.script(event, 503, 200);
        publish(json.writeValueAsString(event));

        await().atMost(TIMEOUT).until(() -> BRANCH.received(event).size() == 2);
        assertThat(findOnDeadLetterTopic(event.eventId().toString())).isNull();
    }

    @Test
    void branchDownForTooLongGoesToTheDeadLetterTopicAfterRetries() {
        BranchSyncEvent event = event("A");
        BRANCH.script(event, 503, 503, 503, 503, 503);
        publish(json.writeValueAsString(event));

        ConsumerRecord<String, String> dead = awaitDeadLetter(event.eventId().toString());
        assertThat(BRANCH.received(event)).hasSize(3);   // 1 attempt + 2 retries
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("Branch answered 503");
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo("dc.branch-a");
        assertThat(json.readValue(dead.value(), BranchSyncEvent.class)).isEqualTo(event);
    }

    @Test
    void rejectedEventIsNotRetried() {
        BranchSyncEvent event = event("A");
        BRANCH.script(event, 422);
        publish(json.writeValueAsString(event));

        ConsumerRecord<String, String> dead = awaitDeadLetter(event.eventId().toString());
        assertThat(BRANCH.received(event)).hasSize(1);
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("rejected the event with 422");
    }

    @Test
    void eventForAnotherBranchIsNeverDelivered() {
        BranchSyncEvent forB = event("B");
        publish(json.writeValueAsString(forB));

        ConsumerRecord<String, String> dead = awaitDeadLetter(forB.eventId().toString());
        assertThat(BRANCH.received(forB)).isEmpty();
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("received an event for branch B");
    }

    @Test
    void unreadableRecordGoesToTheDeadLetterTopicUnchanged() {
        String marker = UUID.randomUUID().toString();
        String garbage = "this is not json " + marker;
        publish(garbage);

        await().atMost(TIMEOUT).until(() -> readAll("dc.branch-a.DLT").stream().anyMatch(r -> garbage.equals(r.value())));
        // and the listener keeps going afterwards
        BranchSyncEvent next = event("A");
        publish(json.writeValueAsString(next));
        await().atMost(TIMEOUT).until(() -> BRANCH.received(next).size() == 1);
    }

    // ---- helpers ----

    private static BranchSyncEvent event(String branch) {
        return BranchSyncEvent.snapshot(branch, Instant.now(),
                List.of(new ProductPrice("P-001", "Coffee", new BigDecimal("42.50"), "MYR")));
    }

    private static void publish(String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("dc.branch-a", "A", value));
        }
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String eventId) {
        await().atMost(TIMEOUT).until(() -> findOnDeadLetterTopic(eventId) != null);
        return findOnDeadLetterTopic(eventId);
    }

    private static ConsumerRecord<String, String> findOnDeadLetterTopic(String eventId) {
        return readAll("dc.branch-a.DLT").stream().filter(r -> r.value().contains(eventId)).findFirst().orElse(null);
    }

    private static List<ConsumerRecord<String, String>> readAll(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = List.of(new TopicPartition(topic, 0));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long end = consumer.endOffsets(partitions).get(partitions.getFirst());
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (consumer.position(partitions.getFirst()) < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /**
     * Stands in for branch A behind its WSO2: POST /events answers 200 unless a status script was set for
     * that event, and records the body and Authorization header. POST /token is the token endpoint: each
     * call issues a new token ("token-1", "token-2", ...) after checking the client's Basic credentials.
     */
    static final class FakeBranch {
        private final HttpServer server;
        private final Map<String, Deque<Integer>> scripts = new ConcurrentHashMap<>();
        private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();
        private final Map<String, List<String>> authorizations = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger tokensIssued = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> tokenRequests = new CopyOnWriteArrayList<>();

        FakeBranch() {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                tokenRequests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + form);
                byte[] reply = ("{\"access_token\":\"token-" + tokensIssued.incrementAndGet()
                        + "\",\"expires_in\":3600,\"scope\":\"branch_a:sync\",\"token_type\":\"Bearer\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.createContext("/events", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                paths.add(exchange.getRequestURI().getPath());
                Matcher m = EVENT_ID.matcher(body);
                String id = m.find() ? m.group(1) : "?";
                bodies.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(body);
                authorizations.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>())
                        .add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                Integer scripted = scripts.getOrDefault(id, new ConcurrentLinkedDeque<>()).pollFirst();
                int status = scripted == null ? 200 : scripted;
                byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void script(BranchSyncEvent event, Integer... statuses) {
            scripts.put(event.eventId().toString(), new ConcurrentLinkedDeque<>(List.of(statuses)));
        }

        List<String> received(BranchSyncEvent event) {
            return bodies.getOrDefault(event.eventId().toString(), List.of());
        }

        List<String> authorizations(BranchSyncEvent event) {
            return authorizations.getOrDefault(event.eventId().toString(), List.of());
        }

        void stop() {
            server.stop(0);
        }
    }
}
