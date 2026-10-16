package com.branchdc.listener;

import com.branchdc.events.PassEvent;
import com.branchdc.events.TravelPass;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Listener A against a real Kafka broker (6 partitions, like the DC creates) and a fake branch (a tiny HTTP
 * server in the test). The fake branch answers per event id with a scripted list of status codes, e.g. 503
 * then 200, and records every request it receives. Logs are JSON, as in Docker and Kubernetes.
 */
@SpringBootTest(properties = {
        "listener.branch-id=A",
        "listener.retry.max-retries=2",
        "listener.retry.initial-interval=100ms",
        "listener.retry.multiplier=1",
        "listener.retry.max-interval=100ms",
        "listener.auth.client-id=listener-a-id",
        "listener.auth.client-secret=listener-a-secret",
        "listener.auth.scope=branch_a:sync",
        "logging.structured.format.console=ecs"
})
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class ListenerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern EVENT_ID = Pattern.compile("\"eventId\"\\s*:\\s*\"([^\"]+)\"");
    private static final int PARTITIONS = 6;
    private static final AtomicLong SEQ = new AtomicLong();

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));
    static final FakeBranch BRANCH = new FakeBranch();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        KAFKA.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // In the real system the DC service creates these.
            admin.createTopics(List.of(new NewTopic("dc.branch-a", PARTITIONS, (short) 1),
                    new NewTopic("dc.branch-a.DLT", PARTITIONS, (short) 1))).all().get();
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
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Test
    void deliversTheEventToItsBranchWithATokenAndTheTraceId() {
        PassEvent event = event("A");
        publish(event.applicationNo(), json.writeValueAsString(event), "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        await().atMost(TIMEOUT).until(() -> BRANCH.received(event).size() == 1);
        PassEvent delivered = json.readValue(BRANCH.received(event).getFirst(), PassEvent.class);
        assertThat(delivered).isEqualTo(event);
        assertThat(BRANCH.paths).contains("/events");
        // with a bearer token from the token endpoint, obtained with the client credentials and scope
        assertThat(BRANCH.authorizations(event).getFirst()).startsWith("Bearer token-");
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("listener-a-id:listener-a-secret".getBytes());
        assertThat(BRANCH.tokenRequests).first().asString()
                .startsWith(basic).contains("grant_type=client_credentials").contains("scope=branch_a%3Async");
        // the DC's trace id travels on to the branch
        assertThat(BRANCH.traceparents(event).getFirst()).contains("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void tokenRejectedByTheGatewayIsReplacedAndTheDeliveryRetried() {
        PassEvent event = event("A");
        BRANCH.script(event, 401, 200);
        publish(event);

        await().atMost(TIMEOUT).until(() -> BRANCH.received(event).size() == 2);
        List<String> auth = BRANCH.authorizations(event);
        assertThat(auth.get(1)).startsWith("Bearer token-").isNotEqualTo(auth.get(0));   // a new token
        assertThat(findOnDeadLetterTopic(event.eventId().toString())).isNull();
    }

    @Test
    void cachedTokenIsReusedForLaterDeliveries() {
        PassEvent first = event("A");
        PassEvent second = event("A");
        publish(first);
        await().atMost(TIMEOUT).until(() -> BRANCH.received(first).size() == 1);
        int tokensBefore = BRANCH.tokenRequests.size();

        publish(second);
        await().atMost(TIMEOUT).until(() -> BRANCH.received(second).size() == 1);
        assertThat(BRANCH.tokenRequests).hasSize(tokensBefore);
        assertThat(BRANCH.authorizations(second)).isEqualTo(BRANCH.authorizations(first));
    }

    @Test
    void branchBrieflyDownIsRetriedAndThenDelivered() {
        PassEvent event = event("A");
        BRANCH.script(event, 503, 200);
        publish(event);

        await().atMost(TIMEOUT).until(() -> BRANCH.received(event).size() == 2);
        assertThat(findOnDeadLetterTopic(event.eventId().toString())).isNull();
    }

    @Test
    void branchDownForTooLongGoesToTheDeadLetterTopicAfterRetries() {
        PassEvent event = event("A");
        BRANCH.script(event, 503, 503, 503, 503, 503);
        publish(event);

        ConsumerRecord<String, String> dead = awaitDeadLetter(event.eventId().toString());
        assertThat(BRANCH.received(event)).hasSize(3);   // 1 attempt + 2 retries
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("Branch answered 503");
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo("dc.branch-a");
        assertThat(json.readValue(dead.value(), PassEvent.class)).isEqualTo(event);
    }

    @Test
    void rejectedEventIsNotRetried() {
        PassEvent event = event("A");
        BRANCH.script(event, 422);
        publish(event);

        ConsumerRecord<String, String> dead = awaitDeadLetter(event.eventId().toString());
        assertThat(BRANCH.received(event)).hasSize(1);
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("rejected the event with 422");
    }

    @Test
    void eventForAnotherBranchIsNeverDelivered() {
        PassEvent forB = event("B");
        publish(forB);

        ConsumerRecord<String, String> dead = awaitDeadLetter(forB.eventId().toString());
        assertThat(BRANCH.received(forB)).isEmpty();
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("received an event for branch B");
    }

    @Test
    void unreadableRecordGoesToTheDeadLetterTopicUnchanged() {
        String garbage = "this is not json " + UUID.randomUUID();
        publish("APP-2026-999999", garbage, null);

        await().atMost(TIMEOUT).until(() -> readAll("dc.branch-a.DLT").stream().anyMatch(r -> garbage.equals(r.value())));
        // and the listener keeps going afterwards (on that partition too)
        PassEvent next = new PassEvent(UUID.randomUUID(), SEQ.incrementAndGet(), PassEvent.Type.DELETE, "A",
                Instant.now(), "APP-2026-999999", null);
        publish(next);
        await().atMost(TIMEOUT).until(() -> BRANCH.received(next).size() == 1);
    }

    /**
     * Scaling: a second copy of the listener (a second pod) joins the group. Kafka shares the 6 partitions
     * between the two, every pass is delivered, and when the second copy stops (scale-down), the first one
     * takes over all partitions and keeps delivering.
     */
    @Test
    void twoListenerCopiesShareThePartitionsAndTakeOverWhenOneStops() {
        awaitAssigned(List.of(registry), PARTITIONS);

        try (ConfigurableApplicationContext second = secondCopy()) {
            KafkaListenerEndpointRegistry secondRegistry = second.getBean(KafkaListenerEndpointRegistry.class);
            await().atMost(TIMEOUT).until(() -> assigned(registry) > 0 && assigned(secondRegistry) > 0
                    && assigned(registry) + assigned(secondRegistry) == PARTITIONS);

            List<PassEvent> rush = IntStream.range(0, 120).mapToObj(i -> event("A")).toList();
            rush.forEach(this::publish);
            await().atMost(TIMEOUT).until(() -> rush.stream().allMatch(e -> !BRANCH.received(e).isEmpty()));
        }

        // the second copy is gone: the first one gets all partitions back and delivers on every one of them
        awaitAssigned(List.of(registry), PARTITIONS);
        List<PassEvent> after = IntStream.range(0, 30).mapToObj(i -> event("A")).toList();
        after.forEach(this::publish);
        await().atMost(TIMEOUT).until(() -> after.stream().allMatch(e -> !BRANCH.received(e).isEmpty()));
    }

    @Test
    void logsNameThePassButNeverTheTraveller(CapturedOutput output) {
        PassEvent delivered = event("A");
        PassEvent rejected = event("A");
        BRANCH.script(rejected, 422);
        publish(delivered);
        publish(rejected);

        await().atMost(TIMEOUT).until(() -> BRANCH.received(delivered).size() == 1);
        awaitDeadLetter(rejected.eventId().toString());

        String line = output.getOut().lines().filter(l -> l.contains("Delivered") && l.contains(delivered.applicationNo()))
                .findFirst().orElseThrow();
        assertThat(json.readTree(line).path("site").asString()).isEqualTo("listener-A");
        for (PassEvent e : List.of(delivered, rejected)) {
            assertThat(output.getAll()).doesNotContain(e.pass().passportNo()).doesNotContain(e.pass().fullName());
        }
    }

    // ---- helpers ----

    /**
     * A second listener A in the same JVM: the same app and settings, i.e. what a second pod would be.
     * Command-line arguments, because they override application.yml (builder "properties" would not).
     */
    private static ConfigurableApplicationContext secondCopy() {
        return new SpringApplicationBuilder(ListenerApplication.class).run(
                "--server.port=0", "--listener.branch-id=A", "--listener.branch-url=" + BRANCH.url(),
                "--listener.auth.token-url=" + BRANCH.url() + "/token", "--listener.auth.client-id=listener-a-id",
                "--listener.auth.client-secret=listener-a-secret", "--listener.auth.scope=branch_a:sync",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(), "--logging.structured.format.console=ecs");
    }

    private static int assigned(KafkaListenerEndpointRegistry registry) {
        return registry.getListenerContainers().stream().map(MessageListenerContainer::getAssignedPartitions)
                .filter(java.util.Objects::nonNull).mapToInt(Collection::size).sum();
    }

    private static void awaitAssigned(List<KafkaListenerEndpointRegistry> registries, int partitions) {
        await().atMost(TIMEOUT).until(() -> registries.stream().mapToInt(ListenerIntegrationTest::assigned).sum() == partitions);
    }

    private static PassEvent event(String branch) {
        String app = "APP-T-" + UUID.randomUUID().toString().substring(0, 8);
        TravelPass pass = new TravelPass("T" + (10_000_000 + SEQ.get() * 7919 % 90_000_000), "Traveller " + app, "THA",
                LocalDate.of(1985, 3, 1), TravelPass.Type.BUSINESS, LocalDate.of(2026, 12, 19), LocalDate.of(2027, 1, 2),
                TravelPass.Status.APPROVED);
        return PassEvent.upsert(SEQ.incrementAndGet(), branch, Instant.now(), app, pass);
    }

    private void publish(PassEvent event) {
        publish(event.applicationNo(), json.writeValueAsString(event), null);
    }

    private static void publish(String key, String value, String traceparent) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record = new ProducerRecord<>("dc.branch-a", key, value);
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(record);
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
            List<TopicPartition> partitions = IntStream.range(0, PARTITIONS).mapToObj(p -> new TopicPartition(topic, p)).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
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
     * that event, and records the body and the Authorization and traceparent headers. POST /token is the token
     * endpoint: each call issues a new token ("token-1", "token-2", ...).
     */
    static final class FakeBranch {
        private final HttpServer server;
        private final Map<String, Deque<Integer>> scripts = new ConcurrentHashMap<>();
        private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();
        private final Map<String, List<String>> authorizations = new ConcurrentHashMap<>();
        private final Map<String, List<String>> traceparents = new ConcurrentHashMap<>();
        private final AtomicInteger tokensIssued = new AtomicInteger();
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> tokenRequests = new CopyOnWriteArrayList<>();

        FakeBranch() {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8)); // two listener copies at once
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
                traceparents.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>())
                        .add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
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

        void script(PassEvent event, Integer... statuses) {
            scripts.put(event.eventId().toString(), new ConcurrentLinkedDeque<>(List.of(statuses)));
        }

        List<String> received(PassEvent event) {
            return bodies.getOrDefault(event.eventId().toString(), List.of());
        }

        List<String> authorizations(PassEvent event) {
            return authorizations.getOrDefault(event.eventId().toString(), List.of());
        }

        List<String> traceparents(PassEvent event) {
            return traceparents.getOrDefault(event.eventId().toString(), List.of());
        }

        void stop() {
            server.stop(0);
        }
    }
}
