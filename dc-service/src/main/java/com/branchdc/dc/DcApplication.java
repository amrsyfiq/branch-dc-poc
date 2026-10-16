package com.branchdc.dc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
public class DcApplication {

    public static void main(String[] args) {
        SpringApplication.run(DcApplication.class, args);
    }

    /**
     * "Now" and "today" (the rush dates its passes from today), in the checkpoints' time zone rather than the
     * container's (UTC): just after midnight in Kuala Lumpur it is still yesterday in UTC. A bean, so tests can
     * fix the date.
     */
    @Bean
    Clock clock(@Value("${dc.time-zone:Asia/Kuala_Lumpur}") ZoneId zone) {
        return Clock.system(zone);
    }
}
