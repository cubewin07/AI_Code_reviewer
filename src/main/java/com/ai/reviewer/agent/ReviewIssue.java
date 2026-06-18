package com.ai.reviewer.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewIssue(
    @JsonProperty("file") String file,
    @JsonProperty("line") Integer line,
    @JsonProperty("severity") String severity,
    @JsonProperty("message") String message
) {}
