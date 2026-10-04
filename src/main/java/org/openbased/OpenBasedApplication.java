package org.openbased;

import org.openbased.config.OpenBasedProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OpenBasedProperties.class)
public class OpenBasedApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenBasedApplication.class, args);
    }
}
