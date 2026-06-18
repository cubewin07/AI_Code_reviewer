package com.ai.reviewer.agent;

import com.ai.reviewer.config.AppConfig;
import com.ai.reviewer.client.NineRouterClient;
import com.ai.reviewer.client.NineRouterClient.ChatCompletionResult;
import com.ai.reviewer.dto.NineRouterDto.ChatMessage;
import com.ai.reviewer.service.PromptBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class ReviewAgent {

    private final NineRouterClient nineRouterClient;
    private final PromptBuilder promptBuilder;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentTool> toolRegistry;

    public ReviewAgent(
            NineRouterClient nineRouterClient,
            PromptBuilder promptBuilder,
            AppConfig appConfig,
            ObjectMapper objectMapper,
            List<AgentTool> tools) {
        this.nineRouterClient = nineRouterClient;
        this.promptBuilder = promptBuilder;
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;

        this.toolRegistry = new HashMap<>();
        for (AgentTool tool : tools) {
            this.toolRegistry.put(tool.getName(), tool);
        }
        log.info("Initialized ReviewAgent with {} tools: {}", toolRegistry.size(), toolRegistry.keySet());
    }

    public ReviewResult review(JobContext context) {
        log.info("Starting code review job {} for repo: {} (Event: {})",
                context.jobId(), context.repoFullName(), context.eventType());

        int maxIterations = appConfig.agent() != null ? appConfig.agent().maxIterations() : 5;
        if (maxIterations <= 0) {
            maxIterations = 5;
        }

        int maxContextTokens = appConfig.agent() != null ? appConfig.agent().maxContextTokens() : 16384;
        if (maxContextTokens <= 0) {
            maxContextTokens = 16384;
        }

        String systemPrompt = buildSystemPrompt();
        List<ChatMessage> conversationHistory = new ArrayList<>();

        // Context assembly
        String initialUserMessage = buildInitialUserContext(context);
        conversationHistory.add(new ChatMessage("user", initialUserMessage));

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("Starting ReAct loop iteration {}/{} for job {}", iteration, maxIterations, context.jobId());

            // Compact conversation to stay within token budget
            List<ChatMessage> compacted = promptBuilder.compactConversation(systemPrompt, conversationHistory, maxContextTokens);

            ChatCompletionResult llmResult;
            try {
                llmResult = nineRouterClient.chat(compacted);
            } catch (Exception e) {
                log.error("LLM call failed during iteration {} for job {}", iteration, context.jobId(), e);
                return new ReviewResult(
                        List.of(),
                        "LLM call failed: " + e.getMessage(),
                        false,
                        "LLM request error: " + e.getMessage(),
                        iteration
                );
            }

            String content = llmResult.content();
            if (content == null || content.isBlank()) {
                log.warn("Received empty response from LLM at iteration {}", iteration);
                conversationHistory.add(new ChatMessage("assistant", "Thought: LLM returned an empty response."));
                conversationHistory.add(new ChatMessage("user", "Observation: Error: Empty response received. Please try again."));
                continue;
            }

            log.debug("LLM response at iteration {}: {}", iteration, content);
            conversationHistory.add(new ChatMessage("assistant", content));

            // Parse LLM response
            int finalAnswerIdx = content.indexOf("Final Answer:");
            if (finalAnswerIdx != -1) {
                String finalAnswerText = content.substring(finalAnswerIdx + "Final Answer:".length()).trim();
                return parseFinalAnswer(finalAnswerText, iteration);
            }

            int actionIdx = content.indexOf("Action:");
            int actionInputIdx = content.indexOf("Action Input:");

            if (actionIdx != -1) {
                String toolName;
                if (actionInputIdx != -1) {
                    toolName = content.substring(actionIdx + "Action:".length(), actionInputIdx).trim();
                } else {
                    toolName = content.substring(actionIdx + "Action:".length()).trim();
                }
                toolName = cleanMarkerValue(toolName);

                String toolInput = "";
                if (actionInputIdx != -1) {
                    toolInput = content.substring(actionInputIdx + "Action Input:".length()).trim();
                    // Cut trailing parts of tool input if model appends other sections (like Observation or Thought)
                    int nextThought = toolInput.indexOf("Thought:");
                    int nextObs = toolInput.indexOf("Observation:");
                    int nextFinal = toolInput.indexOf("Final Answer:");
                    int cutIndex = -1;
                    if (nextThought != -1) cutIndex = nextThought;
                    if (nextObs != -1 && (cutIndex == -1 || nextObs < cutIndex)) cutIndex = nextObs;
                    if (nextFinal != -1 && (cutIndex == -1 || nextFinal < cutIndex)) cutIndex = nextFinal;
                    if (cutIndex != -1) {
                        toolInput = toolInput.substring(0, cutIndex).trim();
                    }
                }

                AgentTool tool = toolRegistry.get(toolName);
                String observation;
                if (tool == null) {
                    observation = "Error: Tool '" + toolName + "' not found. Available tools: " + toolRegistry.keySet();
                } else {
                    try {
                        log.info("Executing tool '{}' with input for job {}", toolName, context.jobId());
                        observation = tool.execute(toolInput, context);
                    } catch (Exception e) {
                        observation = "Error executing tool '" + toolName + "': " + e.getMessage();
                    }
                }

                observation = promptBuilder.truncateObservation(observation, 8000);
                conversationHistory.add(new ChatMessage("user", "Observation: " + observation));
            } else {
                // LLM did not choose an action nor provided a final answer
                log.warn("LLM response did not contain Action or Final Answer at iteration {}", iteration);
                conversationHistory.add(new ChatMessage("user",
                        "Observation: Error: No 'Action:' or 'Final Answer:' marker found in your response. " +
                        "Please specify an Action to take or output 'Final Answer:' with the JSON review list."));
            }
        }

        log.warn("Max iterations ({}) reached without a Final Answer for job {}.", maxIterations, context.jobId());
        return buildTimeoutReviewResult(conversationHistory, maxIterations);
    }

    private ReviewResult parseFinalAnswer(String finalAnswerText, int iterations) {
        try {
            int jsonStart = finalAnswerText.indexOf("[");
            int jsonEnd = finalAnswerText.lastIndexOf("]");
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                String jsonContent = finalAnswerText.substring(jsonStart, jsonEnd + 1);
                List<ReviewIssue> issues = objectMapper.readValue(jsonContent, new TypeReference<List<ReviewIssue>>() {});
                return new ReviewResult(issues, "Review completed successfully.", true, null, iterations);
            } else {
                return new ReviewResult(List.of(), finalAnswerText, false, finalAnswerText, iterations);
            }
        } catch (Exception e) {
            log.warn("Failed to parse structured JSON from Final Answer: {}", finalAnswerText, e);
            return new ReviewResult(List.of(), finalAnswerText, false, finalAnswerText, iterations);
        }
    }

    private ReviewResult buildTimeoutReviewResult(List<ChatMessage> history, int iterations) {
        StringBuilder fallbackBuilder = new StringBuilder();
        fallbackBuilder.append("Warning: The AI agent reached the maximum iteration limit (")
                .append(iterations).append(") before finishing its review.\n\n");
        fallbackBuilder.append("Here is the partial thought process and information gathered:\n\n");

        for (ChatMessage msg : history) {
            if ("assistant".equals(msg.role())) {
                String content = msg.content();
                if (content != null && !content.isBlank()) {
                    for (String line : content.split("\n")) {
                        if (line.trim().startsWith("Thought:")) {
                            fallbackBuilder.append("- ").append(line.trim()).append("\n");
                        }
                    }
                }
            }
        }
        return new ReviewResult(
                List.of(),
                "Partial review due to iteration limit.",
                false,
                fallbackBuilder.toString(),
                iterations
        );
    }

    private String cleanMarkerValue(String val) {
        if (val == null) {
            return "";
        }
        val = val.trim();
        if (val.startsWith("`") && val.endsWith("`") && val.length() > 1) {
            val = val.substring(1, val.length() - 1);
        }
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() > 1) {
            val = val.substring(1, val.length() - 1);
        }
        return val.trim();
    }

    private String buildInitialUserContext(JobContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("We are reviewing code changes for a new event in the repository:\n");
        sb.append("- Repository: ").append(context.repoFullName()).append("\n");
        sb.append("- Event Type: ").append(context.eventType()).append("\n");

        if ("pull_request".equalsIgnoreCase(context.eventType())) {
            sb.append("- PR Number: ").append(context.prNumber()).append("\n");
            sb.append("- Head SHA: ").append(context.commitSha()).append("\n");
        } else {
            sb.append("- Ref: ").append(context.ref()).append("\n");
            sb.append("- Base SHA: ").append(context.baseSha()).append("\n");
            sb.append("- Head SHA: ").append(context.commitSha()).append("\n");
        }

        sb.append("\nPlease use your tools (like 'get_diff') to retrieve the code changes and perform your review.");
        return sb.toString();
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior, highly experienced code reviewer. Your goal is to review code changes in a GitHub repository and provide constructive feedback on potential errors, performance improvements, and code style suggestions.\n\n");
        sb.append("You have access to the following tools to gather context. You can call them one at a time using the ReAct loop format.\n\n");

        for (AgentTool tool : toolRegistry.values()) {
            sb.append("- Tool: `").append(tool.getName()).append("`\n");
            sb.append("  Description: ").append(tool.getDescription()).append("\n\n");
        }

        sb.append("Available tools list: ").append(toolRegistry.keySet()).append("\n\n");

        sb.append("You must use the following format for each turn of your reasoning loop:\n");
        sb.append("Thought: Always think about what information you need and what step to take next.\n");
        sb.append("Action: The tool name to invoke (must be one of: ").append(toolRegistry.keySet()).append(").\n");
        sb.append("Action Input: The input parameter for the tool. For 'get_file_content', use a JSON object, e.g. {\"path\": \"file.java\", \"ref\": \"sha\"}. For other tools, leave empty or use a blank string.\n");
        sb.append("Observation: The output result of the tool. (This will be provided to you by the environment after you specify Action and Action Input).\n\n");

        sb.append("Once you have gathered enough information and are ready to provide your final code review, use the following final response format:\n");
        sb.append("Thought: I now have all the details needed for the review.\n");
        sb.append("Final Answer: [Your review comments structured as a JSON array of issues. Do not include markdown code block formatting around the JSON array inside Final Answer].\n\n");

        sb.append("The JSON array format for Final Answer must exactly follow this schema:\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"file\": \"path/to/file\",\n");
        sb.append("    \"line\": 12,\n");
        sb.append("    \"severity\": \"Error\",\n");
        sb.append("    \"message\": \"Detailed description of the issue, risk, and exact recommendation on how to fix it.\"\n");
        sb.append("  }\n");
        sb.append("]\n\n");

        sb.append("Rules for Severity:\n");
        sb.append("- 'Error': Critical bugs, security flaws, compilation failures, or logical correctness errors.\n");
        sb.append("- 'Warning': Code style smells, potential edge-case issues, suboptimal performance, or minor code smell.\n");
        sb.append("- 'Suggestion': Refactoring suggestions, readability improvements, clean code enhancements.\n\n");

        sb.append("Be concise, precise, and polite in your messages. Good luck!");
        return sb.toString();
    }
}
