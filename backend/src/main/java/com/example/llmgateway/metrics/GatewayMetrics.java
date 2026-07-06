package com.example.llmgateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every observable event is published twice: to Micrometer (per-instance
 * counters/timers scraped by Prometheus for the Grafana dashboard) and to a
 * single shared Redis hash (cluster-wide totals that power the demo UI's
 * live stats, identical no matter which instance serves the page).
 */
@Service
public class GatewayMetrics {

    private static final String STATS = "gw:stats";

    private final MeterRegistry registry;
    private final StringRedisTemplate redis;

    public GatewayMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
    }

    public void request(String cache, String team, long latencyMs) {
        registry.counter("gateway.requests", "cache", cache, "team", team).increment();
        Timer.builder("gateway.request.latency").tag("cache", cache).register(registry)
                .record(Duration.ofMillis(latencyMs));
        incr("requests", 1);
        incr(switch (cache) {
            case "exact" -> "exact_hits";
            case "semantic" -> "semantic_hits";
            default -> "misses";
        }, 1);
    }

    public void rejection(String reason) {
        registry.counter("gateway.rejections", "reason", reason).increment();
        incr("rejected_" + reason, 1);
    }

    public void providerCall(String provider, String outcome) {
        registry.counter("gateway.provider.calls", "provider", provider, "outcome", outcome).increment();
    }

    public void coalesced() {
        registry.counter("gateway.coalesced").increment();
        incr("coalesced", 1);
    }

    public void usage(String team, int promptTokens, int completionTokens, double costUsd) {
        registry.counter("gateway.tokens", "type", "prompt").increment(promptTokens);
        registry.counter("gateway.tokens", "type", "completion").increment(completionTokens);
        registry.counter("gateway.cost.usd", "team", team).increment(costUsd);
        incr("tokens_prompt", promptTokens);
        incr("tokens_completion", completionTokens);
        redis.opsForHash().increment(STATS, "cost_usd", costUsd);
    }

    public void saved(double usd, int tokens) {
        registry.counter("gateway.saved.usd").increment(usd);
        redis.opsForHash().increment(STATS, "saved_usd", usd);
        incr("tokens_saved_cache", tokens);
    }

    public void compressionSaved(int tokens) {
        registry.counter("gateway.compression.tokens.saved").increment(tokens);
        incr("tokens_saved_compression", tokens);
    }

    /** Cluster-wide totals for /admin/stats and the UI. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : redis.opsForHash().entries(STATS).entrySet()) {
            out.put((String) e.getKey(), Double.parseDouble((String) e.getValue()));
        }
        double requests = num(out.get("requests"));
        double hits = num(out.get("exact_hits")) + num(out.get("semantic_hits"));
        out.put("hit_rate", requests == 0 ? 0.0 : Math.round(1000.0 * hits / requests) / 1000.0);
        return out;
    }

    private void incr(String field, long amount) {
        redis.opsForHash().increment(STATS, field, amount);
    }

    private double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
