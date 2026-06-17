package com.ai.reviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppConfig(
    Github github,
    NineRouter nineRouter,
    Worker worker
) {
    public record Github(
        String appId,
        String privateKeyPath,
        String webhookSecret
    ) {}

    public record NineRouter(
        String baseUrl,
        String apiKey,
        String model,
        int maxTokens,
        int timeoutSeconds
    ) {}

    public record Worker(
        int concurrency,
        int maxAttempts
    ) {}
}
