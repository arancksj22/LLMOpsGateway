package com.example.llmgateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI-compatible chat completion request, plus optional gateway controls
 * ({@code cache}, {@code compress}) that override the configured defaults.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Boolean stream,
        Integer maxTokens,
        Boolean cache,
        Boolean compress
) {
}
