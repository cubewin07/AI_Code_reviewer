package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class GithubEventModels {

    private GithubEventModels() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
        @JsonProperty("full_name") String fullName
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PushEvent(
        @JsonProperty("ref") String ref,
        @JsonProperty("before") String before,
        @JsonProperty("after") String after,
        @JsonProperty("repository") Repository repository
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
        @JsonProperty("number") int number,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestEvent(
        @JsonProperty("action") String action,
        @JsonProperty("number") int number,
        @JsonProperty("pull_request") PullRequest pullRequest,
        @JsonProperty("repository") Repository repository
    ) {}
}
