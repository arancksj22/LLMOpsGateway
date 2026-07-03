package com.example.llmgateway.api.dto;

import java.util.List;

public record ChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage,
        GatewayInfo gateway
) {
    public record Choice(int index, Message message, String finishReason) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }

    public record GatewayInfo(
            String cache,           // "exact" | "semantic" | "miss"
            String provider,
            String instance,
            long latencyMs,
            double costUsd,
            double savedUsd,
            boolean compressed,
            int tokensSavedByCompression,
            boolean coalesced
    ) {
    }
}
