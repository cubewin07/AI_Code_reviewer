package com.ai.reviewer.agent.tools;

import com.ai.reviewer.agent.AgentTool;
import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.client.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetFileContentTool implements AgentTool {

    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "get_file_content";
    }

    @Override
    public String getDescription() {
        return "Fetches the full text content of a file. Input format: a JSON object with a 'path' key and optional 'ref' (commit/branch) key, e.g. {\"path\": \"src/main/java/Main.java\", \"ref\": \"abc1234\"}, or simply the file path string.";
    }

    @Override
    public String execute(String actionInput, JobContext context) {
        String path = null;
        String ref = context.commitSha();

        if (actionInput != null && !actionInput.isBlank()) {
            String trimmed = actionInput.trim();
            if (trimmed.startsWith("{")) {
                try {
                    JsonNode node = objectMapper.readTree(trimmed);
                    if (node.has("path")) {
                        path = node.get("path").asText();
                    }
                    if (node.has("ref")) {
                        ref = node.get("ref").asText();
                    }
                } catch (Exception e) {
                    // Ignore and treat as raw string
                }
            }

            if (path == null) {
                // Treat as raw path
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                path = trimmed;
            }
        }

        if (path == null || path.isBlank()) {
            return "Error: File path is empty.";
        }

        try {
            return gitHubClient.getFileContent(
                    context.getOwner(),
                    context.getRepo(),
                    path,
                    ref,
                    context.installationId()
            );
        } catch (Exception e) {
            return "Error fetching file content for " + path + " (ref: " + ref + "): " + e.getMessage();
        }
    }
}
