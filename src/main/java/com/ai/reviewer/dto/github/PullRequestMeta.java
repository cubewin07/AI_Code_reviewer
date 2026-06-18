package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestMeta(
    int number,
    String state,
    String title,
    String body,
    CommitRef head,
    CommitRef base
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitRef(
        String sha,
        String ref,
        Repo repo
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repo(
        @JsonProperty("full_name") String fullName
    ) {}
}
