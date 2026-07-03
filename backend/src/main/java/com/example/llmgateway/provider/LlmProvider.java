package com.example.llmgateway.provider;

import com.example.llmgateway.api.dto.Message;

import java.util.List;
import java.util.function.Consumer;

public interface LlmProvider {

    String name();

    /** True when this provider has the config (API key etc.) it needs. */
    boolean configured();

    ProviderResult complete(List<Message> messages, double temperature, Integer maxTokens);

    /**
     * Streaming completion; {@code onDelta} receives content fragments as they
     * arrive. Default implementation fakes streaming with a single chunk, so
     * only providers with real SSE support need to override.
     */
    default ProviderResult stream(List<Message> messages, double temperature, Integer maxTokens, Consumer<String> onDelta) {
        ProviderResult r = complete(messages, temperature, maxTokens);
        onDelta.accept(r.content());
        return r;
    }

    static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
