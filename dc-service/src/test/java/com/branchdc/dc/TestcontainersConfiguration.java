package com.branchdc.dc;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Real Oracle and Kafka for tests, same images as docker-compose.yml. {@code @ServiceConnection} points
 * the DataSource and Kafka bootstrap servers at the containers. The tests use the image's default
 * FREEPDB1 (one database is all the DC needs); compose adds the per-site PDBs.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Oracle needs well over the 60 s default to start when Docker is busy.
    static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withStartupTimeout(Duration.ofMinutes(4));

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));

    public static String kafkaBootstrapServers() {
        KAFKA.start(); // no-op if already running
        return KAFKA.getBootstrapServers();
    }

    @Bean
    @ServiceConnection
    OracleContainer oracleFreeContainer() {
        return ORACLE;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }
}
