package com.branchdc.branch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/** Real Oracle (same image as docker-compose.yml); the branch schema goes into the image's FREEPDB1. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
                    .withStartupTimeout(Duration.ofMinutes(4));

    @Bean
    @ServiceConnection
    OracleContainer oracleFreeContainer() {
        return ORACLE;
    }
}
