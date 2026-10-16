package com.branchdc.branch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BranchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BranchApplication.class, args);
    }

    /**
     * "Today" for the counter check (is the pass valid today?), in the checkpoint's time zone rather than the
     * container's (UTC). A bean, so tests can fix the date.
     */
    @Bean
    Clock clock(@Value("${branch.time-zone:Asia/Kuala_Lumpur}") ZoneId zone) {
        return Clock.system(zone);
    }
}
