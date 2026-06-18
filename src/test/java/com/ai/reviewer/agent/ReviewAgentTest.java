package com.ai.reviewer.agent;

import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.client.NineRouterClient;
import com.ai.reviewer.client.NineRouterClient.ChatCompletionResult;
import com.ai.reviewer.service.PromptBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewAgentTest {

    private NineRouterClient nineRouterClient;
    private PromptBuilder promptBuilder;
    private AppConfig appConfig;
    private ObjectMapper objectMapper;
    private List<AgentTool> tools;
    private AgentTool mockTool;

    @BeforeEach
    void setUp() {
        nineRouterClient = mock(NineRouterClient.class);
        promptBuilder = new PromptBuilder();
        appConfig = mock(AppConfig.class);
        objectMapper = new ObjectMapper();

        AppConfig.Agent agentConfig = new AppConfig.Agent(3, 16384);
        when(appConfig.agent()).thenReturn(agentConfig);

        mockTool = mock(AgentTool.class);
        when(mockTool.getName()).thenReturn("mock_tool");
        when(mockTool.getDescription()).thenReturn("A mock tool for testing.");
        when(mockTool.execute(anyString(), any())).thenReturn("Mock Tool Observation");

        tools = List.of(mockTool);
    }

    @Test
    void testSuccessfulReActLoop() {
        ChatCompletionResult res1 = new ChatCompletionResult(
                "Thought: I need to run mock_tool first.\nAction: mock_tool\nAction Input: {}\n",
                "stop"
        );
        ChatCompletionResult res2 = new ChatCompletionResult(
                "Thought: I have the info.\nFinal Answer: [\n" +
                "  {\"file\": \"App.java\", \"line\": 10, \"severity\": \"Error\", \"message\": \"Null risk\"}\n" +
                "]",
                "stop"
        );

        when(nineRouterClient.chat(anyList()))
                .thenReturn(res1)
                .thenReturn(res2);

        ReviewAgent agent = new ReviewAgent(nineRouterClient, promptBuilder, appConfig, objectMapper, tools);

        JobContext context = new JobContext(
                1L, "pull_request", "owner/repo", 12345L,
                1, "headSha", "refs/heads/main", "baseSha", "headSha"
        );

        ReviewResult result = agent.review(context);

        assertTrue(result.success());
        assertEquals(1, result.issues().size());
        assertEquals("App.java", result.issues().get(0).file());
        assertEquals(10, result.issues().get(0).line());
        assertEquals("Error", result.issues().get(0).severity());
        assertEquals("Null risk", result.issues().get(0).message());
        assertEquals(2, result.iterations());

        verify(mockTool, times(1)).execute(anyString(), eq(context));
    }

    @Test
    void testMaxIterationsGuard() {
        ChatCompletionResult res = new ChatCompletionResult(
                "Thought: Still thinking.\nAction: mock_tool\nAction Input: {}\n",
                "stop"
        );
        when(nineRouterClient.chat(anyList())).thenReturn(res);

        ReviewAgent agent = new ReviewAgent(nineRouterClient, promptBuilder, appConfig, objectMapper, tools);

        JobContext context = new JobContext(
                1L, "pull_request", "owner/repo", 12345L,
                1, "headSha", "refs/heads/main", "baseSha", "headSha"
        );

        ReviewResult result = agent.review(context);

        assertFalse(result.success());
        assertTrue(result.issues().isEmpty());
        assertEquals(3, result.iterations());
        assertNotNull(result.rawMarkdownFallback());
        assertTrue(result.rawMarkdownFallback().contains("Warning: The AI agent reached the maximum iteration limit"));
    }

    @Test
    void testReviewFormatter() {
        ReviewFormatter formatter = new ReviewFormatter();

        List<ReviewIssue> issues = List.of(
                new ReviewIssue("Main.java", 5, "Error", "Resource leak"),
                new ReviewIssue("Utils.java", 20, "Warning", "Style mismatch"),
                new ReviewIssue("Test.java", 1, "Suggestion", "Use assertions")
        );
        ReviewResult result = new ReviewResult(issues, "Summary details", true, null, 1);

        String markdown = formatter.format(result);

        assertTrue(markdown.contains("#### 🔴 Errors"));
        assertTrue(markdown.contains("`Main.java`:5 — Resource leak"));
        assertTrue(markdown.contains("#### 🟡 Warnings"));
        assertTrue(markdown.contains("`Utils.java`:20 — Style mismatch"));
        assertTrue(markdown.contains("#### 🔵 Suggestions"));
        assertTrue(markdown.contains("`Test.java`:1 — Use assertions"));
    }
}
