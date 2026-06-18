package com.ai.reviewer.client;

import com.ai.reviewer.dto.NineRouterDto.ChatMessage;
import com.ai.reviewer.service.PromptBuilder;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpServerErrorException;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class NineRouterClientTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private NineRouterClient nineRouterClient;

    @Autowired
    private PromptBuilder promptBuilder;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.nine-router.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    void testChatSuccess() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer mock-api-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-70b-instruct")))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chat-123",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "content": "Review content"
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ]
                                }
                                """)));

        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));
        NineRouterClient.ChatCompletionResult result = nineRouterClient.chat(messages);

        assertThat(result.content()).isEqualTo("Review content");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void testChatFinishReasonLength() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chat-123",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "content": "Review truncated content"
                                      },
                                      "finish_reason": "length"
                                    }
                                  ]
                                }
                                """)));

        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));
        NineRouterClient.ChatCompletionResult result = nineRouterClient.chat(messages);

        assertThat(result.content()).isEqualTo("Review truncated content");
        assertThat(result.finishReason()).isEqualTo("length");
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void testChatRetryOn5xxAndSucceed() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("First Failure"));

        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("First Failure")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "choices": [
                                    {
                                      "message": {
                                        "role": "assistant",
                                        "content": "Success after retry"
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ]
                                }
                                """)));

        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));
        // This test runs synchronously and will sleep for 1000ms on first failure
        NineRouterClient.ChatCompletionResult result = nineRouterClient.chat(messages);

        assertThat(result.content()).isEqualTo("Success after retry");
        verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void testChatFallbackModelTriggeredOnCompleteFailure() {
        // Stub primary model requests to all fail 5xx (3 attempts)
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-70b-instruct")))
                .willReturn(aResponse().withStatus(500)));

        // Stub fallback model request to succeed
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-8b-instruct")))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "choices": [
                                    {
                                      "message": {
                                        "role": "assistant",
                                        "content": "Success from fallback model"
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ]
                                }
                                """)));

        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));
        // This will attempt primary model 3 times (with 1000ms, 2000ms delay), then try fallback model.
        // To speed up test execution, we could mock the timings or just let it sleep. 
        // 3 seconds of sleep total is acceptable for this test.
        NineRouterClient.ChatCompletionResult result = nineRouterClient.chat(messages);

        assertThat(result.content()).isEqualTo("Success from fallback model");
        
        // Verify primary model was requested 3 times
        verify(3, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-70b-instruct"))));
        
        // Verify fallback model was requested 1 time
        verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-8b-instruct"))));
    }

    @Test
    void testChatFailsWhenBothModelsFail() {
        // Primary fails 500
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-70b-instruct")))
                .willReturn(aResponse().withStatus(500)));

        // Fallback fails 500
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3-8b-instruct")))
                .willReturn(aResponse().withStatus(500)));

        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));

        assertThatThrownBy(() -> nineRouterClient.chat(messages))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void testPromptBuilderTrimming() {
        String systemPrompt = "You are a reviewer.";
        String scratchpad = "Thought: Step 1. Observation: Diff lines... ".repeat(100); // ~4300 chars, ~1075 tokens
        
        // Budget is ample: no trimming
        List<ChatMessage> promptAmple = promptBuilder.buildPrompt(systemPrompt, scratchpad, 2000);
        assertThat(promptAmple).hasSize(2);
        assertThat(promptAmple.get(0).content()).isEqualTo(systemPrompt);
        assertThat(promptAmple.get(1).content()).isEqualTo(scratchpad);

        // Budget is small
        List<ChatMessage> promptTrimmed = promptBuilder.buildPrompt(systemPrompt, scratchpad, 200);
        assertThat(promptTrimmed).hasSize(2);
        assertThat(promptTrimmed.get(0).content()).isEqualTo(systemPrompt);
        assertThat(promptTrimmed.get(1).content()).startsWith("... [truncated due to token limit] ...");
        assertThat(promptTrimmed.get(1).content().length()).isLessThan(scratchpad.length());
    }

    @Test
    void testCompactConversation_noCompactionNeeded() {
        String systemPrompt = "You are a code reviewer.";
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "Review PR #5"),
                new ChatMessage("assistant", "Thought: I need the diff.\nAction: get_diff"),
                new ChatMessage("user", "Observation: file.java modified +5 -2")
        );

        List<ChatMessage> result = promptBuilder.compactConversation(systemPrompt, history, 4096);

        assertThat(result).hasSize(4); // system + 3 history messages
        assertThat(result.get(0).role()).isEqualTo("system");
        assertThat(result.get(0).content()).isEqualTo(systemPrompt);
        assertThat(result.get(1).content()).isEqualTo("Review PR #5");
        assertThat(result.get(2).content()).contains("get_diff");
        assertThat(result.get(3).content()).contains("file.java");
    }

    @Test
    void testCompactConversation_phase1_observationCaps() {
        String systemPrompt = "You are a code reviewer.";
        String largeObservation = "x".repeat(20000); // 20k chars, well over the 8000 cap
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "Review PR #5"),
                new ChatMessage("assistant", "Thought: get diff"),
                new ChatMessage("user", "Observation: " + largeObservation)
        );

        // Budget must be smaller than raw total (~5100 tokens) but larger than
        // capped total (~2100 tokens), so Phase 1 triggers but Phase 2 does not.
        List<ChatMessage> result = promptBuilder.compactConversation(systemPrompt, history, 3000);

        assertThat(result).hasSize(4);
        // The observation message (3rd user message = index 3) should be truncated
        String observationContent = result.get(3).content();
        assertThat(observationContent.length()).isLessThan(largeObservation.length() + 20);
        assertThat(observationContent).contains("truncated due to size limit");
    }

    @Test
    void testCompactConversation_phase2_slidingWindow() {
        String systemPrompt = "Review code.";
        // Build a long conversation: 5 thought+observation pairs
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("user", "Review PR #5. " + "context ".repeat(50)));
        for (int i = 1; i <= 5; i++) {
            history.add(new ChatMessage("assistant", "Thought " + i + ": " + "reasoning ".repeat(100)));
            history.add(new ChatMessage("user", "Observation " + i + ": " + "data ".repeat(100)));
        }

        // Set a tight budget so sliding window kicks in
        int totalTokens = promptBuilder.estimateTokens(systemPrompt) + 20;
        for (ChatMessage msg : history) {
            totalTokens += promptBuilder.estimateTokens(msg.content()) + 20;
        }
        int tightBudget = totalTokens / 2; // roughly half the needed space

        List<ChatMessage> result = promptBuilder.compactConversation(systemPrompt, history, tightBudget);

        // System prompt should always be first
        assertThat(result.get(0).role()).isEqualTo("system");
        assertThat(result.get(0).content()).isEqualTo(systemPrompt);
        // Initial user context should be preserved (second message)
        assertThat(result.get(1).role()).isEqualTo("user");
        assertThat(result.get(1).content()).startsWith("Review PR #5.");
        // Result should be smaller than input
        assertThat(result.size()).isLessThan(history.size() + 1);
        // Should contain a dropped-messages marker
        boolean hasDropMarker = result.stream()
                .anyMatch(m -> m.content().contains("earlier messages were dropped"));
        assertThat(hasDropMarker).isTrue();
        // The latest observations should be preserved (Observation 5 should survive)
        boolean hasLatest = result.stream()
                .anyMatch(m -> m.content().contains("Observation 5"));
        assertThat(hasLatest).isTrue();
    }

    @Test
    void testCompactConversation_phase3_aggressiveTruncation() {
        String systemPrompt = "Review.";
        // Single long user message that can't be windowed away
        String longContent = "important data ".repeat(500); // ~7500 chars
        List<ChatMessage> history = List.of(
                new ChatMessage("user", longContent)
        );

        // Very tight budget: force phase 3
        List<ChatMessage> result = promptBuilder.compactConversation(systemPrompt, history, 100);

        assertThat(result.get(0).role()).isEqualTo("system");
        // User message should be aggressively truncated
        assertThat(result.get(1).content().length()).isLessThan(longContent.length());
    }

    @Test
    void testCompactConversation_nullInputsHandled() {
        List<ChatMessage> result = promptBuilder.compactConversation(null, null, 4096);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo("system");
        assertThat(result.get(0).content()).isEmpty();
    }

    @Test
    void testEstimateTokensForMessages() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "Hello"),     // 2 tokens + 20 overhead = 22
                new ChatMessage("user", "World")         // 2 tokens + 20 overhead = 22
        );
        int tokens = promptBuilder.estimateTokens(messages);
        // "Hello" = (5+3)/4 = 2, "World" = (5+3)/4 = 2; each + 20 overhead → 44 total
        assertThat(tokens).isEqualTo(44);
    }

    @Test
    void testTruncateObservation() {
        String content = "abcdefghij"; // 10 chars
        assertThat(promptBuilder.truncateObservation(content, 5))
                .startsWith("abcde")
                .contains("truncated due to size limit");
        assertThat(promptBuilder.truncateObservation(content, 100)).isEqualTo(content);
        assertThat(promptBuilder.truncateObservation(null, 100)).isNull();
    }
}
