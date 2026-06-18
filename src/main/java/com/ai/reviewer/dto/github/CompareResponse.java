package com.ai.reviewer.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CompareResponse(
    List<FileDiff> files
) {}
