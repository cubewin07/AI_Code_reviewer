package com.ai.reviewer.agent.tools;

import com.ai.reviewer.agent.AgentTool;
import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.client.GitHubClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetReadmeTool implements AgentTool {

    private final GitHubClient gitHubClient;

    @Override
    public String getName() {
        return "get_readme";
    }

    @Override
    public String getDescription() {
        return "Fetches the README file of the repository to understand the repository purpose. No input required.";
    }

    @Override
    public String execute(String actionInput, JobContext context) {
        try {
            return gitHubClient.getReadme(
                    context.getOwner(),
                    context.getRepo(),
                    context.installationId()
            );
        } catch (Exception e) {
            return "Error fetching README: " + e.getMessage();
        }
    }
}
