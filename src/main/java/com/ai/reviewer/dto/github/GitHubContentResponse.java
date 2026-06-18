package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubContentResponse(
    String type,
    String encoding,
    long size,
    String name,
    String path,
    String content,
    String sha
) {}
