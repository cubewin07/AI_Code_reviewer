package com.ai.reviewer.client;

import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.dto.NineRouterDto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

@Component
@Slf4j
public class NineRouterClient {

    private final AppConfig appConfig;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public NineRouterClient(AppConfig appConfig, MeterRegistry meterRegistry) {
        this.appConfig = appConfig;
        this.meterRegistry = meterRegistry;
        
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(appConfig.nineRouter().timeoutSeconds()).toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        this.restClient = RestClient.builder()
                .baseUrl(appConfig.nineRouter().baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public record ChatCompletionResult(String content, String finishReason) {
        public boolean isTruncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }

    /**
     * Executes the chat completion request. Attempts using primary model (with retries),
     * and falls back to fallback model if configured and primary fails.
     */
    public ChatCompletionResult chat(List<ChatMessage> messages) {
        String primaryModel = appConfig.nineRouter().model();
        try {
            return executeChatWithRetry(primaryModel, messages);
        } catch (Exception e) {
            String fallback = appConfig.nineRouter().fallbackModel();
            if (fallback != null && !fallback.trim().isEmpty() && !fallback.equals(primaryModel)) {
                log.warn("Primary model ({}) failed. Falling back to model: {}", primaryModel, fallback, e);
                return executeChatWithRetry(fallback, messages);
            }
            throw e;
        }
    }

    private ChatCompletionResult executeChatWithRetry(String model, List<ChatMessage> messages) {
        int maxAttempts = 3;
        long backoffMs = 1000;
        double multiplier = 2.0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeRequest(model, messages);
            } catch (HttpServerErrorException e) {
                // Exponential backoff on 5xx errors
                if (attempt == maxAttempts) {
                    log.error("Failed to execute LLM request after {} attempts for model {}", maxAttempts, model, e);
                    throw e;
                }
                log.warn("Attempt {} failed with 5xx server error ({}). Retrying in {}ms...", 
                        attempt, e.getStatusCode(), backoffMs, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry backoff interrupted", ie);
                }
                backoffMs = (long) (backoffMs * multiplier);
            }
        }
        throw new IllegalStateException("Unexpected end of retry loop");
    }

    private ChatCompletionResult executeRequest(String model, List<ChatMessage> messages) {
        ChatCompletionRequest request = new ChatCompletionRequest(
                model,
                messages,
                appConfig.nineRouter().maxTokens(),
                null, // Use default temperature
                false // Disable streaming
        );

        log.info("Sending chat completion request to 9Router. Model: {}, Messages count: {}", model, messages.size());
        
        long startTime = System.currentTimeMillis();
        ChatCompletionResponse response;
        try {
            String uri = "/v1/chat/completions";
            String configuredBaseUrl = appConfig.nineRouter().baseUrl();
            if (configuredBaseUrl != null && (configuredBaseUrl.endsWith("/v1") || configuredBaseUrl.endsWith("/v1/"))) {
                uri = "/chat/completions";
            }
            
            RestClient.RequestBodySpec bodySpec = restClient.post()
                    .uri(uri);
            
            String apiKey = appConfig.nineRouter().apiKey();
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                bodySpec.header("Authorization", "Bearer " + apiKey);
            }
            
            response = bodySpec
                    .body(request)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            log.info("9Router request for model {} completed in {}ms", model, duration);
            Timer.builder("llm.latency")
                    .tag("model", model)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(duration));
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new RestClientException("Invalid response from 9Router: empty choices");
        }

        Choice choice = response.choices().get(0);
        String content = choice.message().content();
        String finishReason = choice.finishReason();

        if ("length".equalsIgnoreCase(finishReason)) {
            log.warn("Response generation was truncated because it reached the maximum token length limit.");
        }

        return new ChatCompletionResult(content, finishReason);
    }
}
