package com.ai.reviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppConfig(
    Github github,
    NineRouter nineRouter,
    Worker worker,
    Agent agent
) {
    public record Github(
        String appId,
        String privateKeyPath,
        String webhookSecret,
        String apiUrl
    ) {}

    public record NineRouter(
        String baseUrl,
        String apiKey,
        String model,
        String fallbackModel,
        int maxTokens,
        int timeoutSeconds
    ) {}

    public record Worker(
        int concurrency,
        int maxAttempts
    ) {}

    public record Agent(
        int maxIterations,
        int maxContextTokens
    ) {}
}
