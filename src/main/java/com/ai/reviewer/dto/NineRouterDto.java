package com.ai.reviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class NineRouterDto {

    private NineRouterDto() {}

    public record ChatMessage(
            String role,
            String content
    ) {}

    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            Double temperature
    ) {}

    public record Choice(
            int index,
            ChatMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    public record ChatCompletionResponse(
            String id,
            List<Choice> choices
    ) {}
}
