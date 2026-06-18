package com.ai.reviewer.agent;

import java.util.List;

public record ReviewResult(
    List<ReviewIssue> issues,
    String summary,
    boolean success,
    String rawMarkdownFallback,
    int iterations
) {}
