package com.ai.reviewer.agent.tools;

import com.ai.reviewer.agent.AgentTool;
import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.client.GitHubClient;
import com.ai.reviewer.dto.github.PullRequestMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetPullRequestMetadataTool implements AgentTool {

    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "get_pr_metadata";
    }

    @Override
    public String getDescription() {
        return "Fetches metadata of the pull request (such as title, description, branch names, etc.). No input required.";
    }

    @Override
    public String execute(String actionInput, JobContext context) {
        if (context.prNumber() == null) {
            return "Error: This is a push event, not a pull request event. No pull request metadata is available.";
        }
        try {
            PullRequestMeta meta = gitHubClient.getPullRequestMetadata(
                    context.getOwner(),
                    context.getRepo(),
                    context.prNumber(),
                    context.installationId()
            );
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "Error fetching PR metadata: " + e.getMessage();
        }
    }
}
