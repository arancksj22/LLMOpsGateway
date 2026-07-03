package com.example.llmgateway.provider;

public record ProviderResult(
        String content,
        String model,
        String provider,
        int promptTokens,
        int completionTokens
) {
}
