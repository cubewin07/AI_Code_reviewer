package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubComment(
    Long id,
    String body
) {}
