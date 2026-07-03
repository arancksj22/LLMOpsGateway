package com.example.llmgateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Thin wrapper publishing all gateway metrics to Micrometer/Prometheus. */
@Service
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(String cache, String team, long latencyMs) {
        registry.counter("gateway.requests", "cache", cache, "team", team).increment();
        Timer.builder("gateway.request.latency")
                .tag("cache", cache)
                .register(registry)
                .record(Duration.ofMillis(latencyMs));
    }

    public void rejection(String reason) {
        registry.counter("gateway.rejections", "reason", reason).increment();
    }

    public void providerCall(String provider, String outcome) {
        registry.counter("gateway.provider.calls", "provider", provider, "outcome", outcome).increment();
    }

    public void coalesced() {
        registry.counter("gateway.coalesced").increment();
    }

    public void tokens(int prompt, int completion) {
        registry.counter("gateway.tokens", "type", "prompt").increment(prompt);
        registry.counter("gateway.tokens", "type", "completion").increment(completion);
    }

    public void cost(String team, double usd) {
        registry.counter("gateway.cost.usd", "team", team).increment(usd);
    }

    public void saved(double usd) {
        registry.counter("gateway.saved.usd").increment(usd);
    }

    public void compressionSaved(int tokens) {
        registry.counter("gateway.compression.tokens.saved").increment(tokens);
    }
}
