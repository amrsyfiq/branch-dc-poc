package com.branchdc.listener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ListenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ListenerApplication.class, args);
    }
}
