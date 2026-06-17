package com.ai.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiCodeReviewerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCodeReviewerApplication.class, args);
    }
}
