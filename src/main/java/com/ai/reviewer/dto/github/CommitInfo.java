package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitInfo(
    String sha,
    CommitDetail commit
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitDetail(
        String message,
        Author author
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(
        String name,
        String email,
        String date
    ) {}
}
