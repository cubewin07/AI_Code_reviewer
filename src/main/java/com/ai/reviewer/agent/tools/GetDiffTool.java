package com.ai.reviewer.agent.tools;

import com.ai.reviewer.agent.AgentTool;
import com.ai.reviewer.agent.JobContext;
import com.ai.reviewer.client.GitHubClient;
import com.ai.reviewer.dto.github.FileDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GetDiffTool implements AgentTool {

    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "get_diff";
    }

    @Override
    public String getDescription() {
        return "Fetches the file diff (changed files, patches, additions/deletions/changes details) for the current PR or push event. No input required.";
    }

    @Override
    public String execute(String actionInput, JobContext context) {
        try {
            List<FileDiff> diffs;
            if ("pull_request".equalsIgnoreCase(context.eventType())) {
                if (context.prNumber() == null) {
                    return "Error: Event type is pull_request but PR number is missing from job context.";
                }
                diffs = gitHubClient.getPullRequestDiff(
                        context.getOwner(),
                        context.getRepo(),
                        context.prNumber(),
                        context.installationId()
                );
            } else if ("push".equalsIgnoreCase(context.eventType())) {
                if (context.baseSha() == null || context.headSha() == null) {
                    return "Error: Event type is push but base/head commit SHAs are missing from job context.";
                }
                diffs = gitHubClient.getCommitDiff(
                        context.getOwner(),
                        context.getRepo(),
                        context.baseSha(),
                        context.headSha(),
                        context.installationId()
                );
            } else {
                return "Error: Unsupported event type: " + context.eventType();
            }

            return objectMapper.writeValueAsString(diffs);
        } catch (Exception e) {
            return "Error fetching diff: " + e.getMessage();
        }
    }
}
