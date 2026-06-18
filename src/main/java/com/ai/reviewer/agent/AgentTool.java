package com.ai.reviewer.agent;

public interface AgentTool {
    String getName();
    String getDescription();
    String execute(String actionInput, JobContext context);
}
