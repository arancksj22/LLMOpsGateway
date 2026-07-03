package com.example.llmgateway.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CachedResponse(
        String content,
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        long ts
) {
}
