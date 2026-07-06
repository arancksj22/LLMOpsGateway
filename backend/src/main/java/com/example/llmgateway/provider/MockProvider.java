package com.example.llmgateway.provider;

import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Deterministic no-cost provider, last in the failover order: it lets the
 * whole cluster and 100K+-request load tests run with no API keys at $0
 * while exercising the exact same gateway code paths (real pricing applied).
 */
@Component
public class MockProvider implements LlmProvider {

    private final GatewayProperties props;

    public MockProvider(GatewayProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public ProviderResult complete(List<Message> messages, double temperature, Integer maxTokens) {
        try {
            Thread.sleep(props.providers().mock().latencyMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String prompt = messages.get(messages.size() - 1).content();
        String content = "This is a simulated LLM answer produced by the gateway's mock provider "
                + "(used for keyless demos and load benchmarking). Your prompt was "
                + prompt.split("\\s+").length + " words long and hashed to "
                + Integer.toHexString(prompt.hashCode()) + ".";
        int promptTokens = 0;
        for (Message m : messages) {
            promptTokens += m.content().length() / 4;
        }
        return new ProviderResult(content, props.providers().mock().model(), name(),
                Math.max(1, promptTokens), content.length() / 4);
    }

    @Override
    public ProviderResult stream(List<Message> messages, double temperature, Integer maxTokens, Consumer<String> onDelta) {
        ProviderResult r = complete(messages, temperature, maxTokens);
        for (String word : r.content().split(" ")) {
            onDelta.accept(word + " ");
        }
        return r;
    }
}
