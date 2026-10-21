package com.blockland.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BlockLandBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlockLandBackendApplication.class, args);
    }
}
