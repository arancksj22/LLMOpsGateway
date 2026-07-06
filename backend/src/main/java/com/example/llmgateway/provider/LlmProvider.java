package com.example.llmgateway.provider;

import com.example.llmgateway.api.dto.Message;

import java.util.List;
import java.util.function.Consumer;

/** One upstream LLM (Groq / Gemini / mock). The router tries them in order. */
public interface LlmProvider {

    String name();

    /** True when this provider has the config (API key etc.) it needs. */
    boolean configured();

    ProviderResult complete(List<Message> messages, double temperature, Integer maxTokens);

    /**
     * Streaming completion; {@code onDelta} receives content fragments as they
     * arrive. Default fakes streaming with a single chunk, so only providers
     * with real SSE support (Groq) need to override.
     */
    default ProviderResult stream(List<Message> messages, double temperature, Integer maxTokens, Consumer<String> onDelta) {
        ProviderResult r = complete(messages, temperature, maxTokens);
        onDelta.accept(r.content());
        return r;
    }
}
