package com.ai.reviewer.client;

import com.ai.reviewer.dto.NineRouterDto.ChatMessage;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NineRouterClientLiveTest {

    private static String apiKey;
    private static String model;
    private static String baseUrl;

    static {
        // Load .env before Spring context initializes
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        apiKey = dotenv.get("NINE_ROUTER_API_KEY");
        model = dotenv.get("NINE_ROUTER_MODEL");
        baseUrl = dotenv.get("NINE_ROUTER_BASE_URL");
        
        // Default to local 9Router proxy if base URL is not set
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:20128";
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (apiKey != null && !apiKey.isEmpty()) {
            registry.add("app.nine-router.api-key", () -> apiKey);
        }
        if (model != null && !model.isEmpty()) {
            registry.add("app.nine-router.model", () -> model);
        }
        if (baseUrl != null && !baseUrl.isEmpty()) {
            registry.add("app.nine-router.base-url", () -> baseUrl);
        }
    }

    @Autowired
    private NineRouterClient nineRouterClient;

    @Test
    void testLiveChatCompletion() {
        // Only run if api key is provided and not the default mock
        org.junit.jupiter.api.Assumptions.assumeTrue(apiKey != null && !apiKey.isEmpty() && !apiKey.equals("mock-api-key"),
                "Skipping live test because NINE_ROUTER_API_KEY is not set in .env");

        System.out.println("Running live 9Router integration test with model: " + model + " and baseUrl: " + baseUrl);

        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "You are a helpful assistant."),
                new ChatMessage("user", "Hello, reply with exactly the word 'ACK' and nothing else.")
        );

        NineRouterClient.ChatCompletionResult result = nineRouterClient.chat(messages);

        System.out.println("Live Chat Response finish reason: " + result.finishReason());
        System.out.println("Live Chat Response content: " + result.content());

        assertThat(result.content()).isNotBlank();
    }
}
