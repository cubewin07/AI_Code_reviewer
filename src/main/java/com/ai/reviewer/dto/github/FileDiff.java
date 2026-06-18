package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FileDiff(
    String filename,
    String status,
    int additions,
    int deletions,
    int changes,
    String patch
) {}
