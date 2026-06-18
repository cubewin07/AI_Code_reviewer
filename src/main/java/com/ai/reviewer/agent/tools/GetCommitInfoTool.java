package com.ai.reviewer.agent.tools;

import com.ai.reviewer.agent.AgentTool;
import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.client.GitHubClient;
import com.ai.reviewer.dto.github.CommitInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetCommitInfoTool implements AgentTool {

    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "get_commit_info";
    }

    @Override
    public String getDescription() {
        return "Fetches information about a specific commit (commit message, author, date, etc.). Input format: the commit SHA string, or JSON containing a 'sha' key.";
    }

    @Override
    public String execute(String actionInput, JobContext context) {
        String sha = extractSha(actionInput, context);
        if (sha == null || sha.isBlank()) {
            return "Error: No commit SHA specified or available in the context.";
        }
        try {
            CommitInfo info = gitHubClient.getCommitInfo(
                    context.getOwner(),
                    context.getRepo(),
                    sha,
                    context.installationId()
            );
            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            return "Error fetching commit info for SHA " + sha + ": " + e.getMessage();
        }
    }

    private String extractSha(String input, JobContext context) {
        if (input == null || input.isBlank()) {
            return context.commitSha();
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                if (node.has("sha")) {
                    return node.get("sha").asText();
                }
                if (node.has("commitSha")) {
                    return node.get("commitSha").asText();
                }
            } catch (Exception e) {
                // Ignore and fall back to raw string
            }
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? context.commitSha() : trimmed;
    }
}
