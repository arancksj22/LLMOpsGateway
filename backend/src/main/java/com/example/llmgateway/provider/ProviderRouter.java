package com.example.llmgateway.provider;

import com.example.llmgateway.api.GatewayException;
import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Multi-provider failover: providers are tried in configured order
 * (groq -> gemini -> mock by default); a provider that keeps failing is
 * skipped by its circuit breaker for a cooldown, then given one probe.
 * Switching or reordering providers is one config change for every app.
 */
@Service
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final Map<String, LlmProvider> providers = new HashMap<>();
    private final Map<String, CircuitBreaker> breakers = new HashMap<>();
    private final GatewayProperties props;
    private final GatewayMetrics metrics;

    public ProviderRouter(List<LlmProvider> providerList, GatewayProperties props, GatewayMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
        for (LlmProvider p : providerList) {
            providers.put(p.name(), p);
            breakers.put(p.name(), new CircuitBreaker(
                    props.circuitBreaker().failureThreshold(),
                    props.circuitBreaker().cooldownSeconds() * 1000L));
        }
    }

    public ProviderResult complete(List<Message> messages, double temperature, Integer maxTokens) {
        return route(p -> p.complete(messages, temperature, maxTokens));
    }

    public ProviderResult stream(List<Message> messages, double temperature, Integer maxTokens, Consumer<String> onDelta) {
        return route(p -> p.stream(messages, temperature, maxTokens, onDelta));
    }

    private interface Call {
        ProviderResult apply(LlmProvider p);
    }

    private ProviderResult route(Call call) {
        Exception last = null;
        for (String name : props.providers().order()) {
            LlmProvider p = providers.get(name.trim());
            if (p == null || !p.configured()) {
                continue;
            }
            CircuitBreaker cb = breakers.get(p.name());
            if (!cb.allow()) {
                metrics.providerCall(p.name(), "circuit_open");
                continue;
            }
            try {
                ProviderResult r = call.apply(p);
                cb.success();
                metrics.providerCall(p.name(), "success");
                return r;
            } catch (Exception e) {
                cb.failure();
                metrics.providerCall(p.name(), "error");
                log.warn("Provider {} failed: {}", p.name(), e.getMessage());
                last = e;
            }
        }
        throw new GatewayException(HttpStatus.BAD_GATEWAY, "all_providers_failed",
                "No provider could serve the request" + (last != null ? ": " + last.getMessage() : ""));
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new HashMap<>();
        for (String name : props.providers().order()) {
            LlmProvider p = providers.get(name.trim());
            if (p != null) {
                out.put(p.name(), Map.of(
                        "configured", p.configured(),
                        "circuit_open", breakers.get(p.name()).isOpen()));
            }
        }
        return out;
    }

    /** Opens after N consecutive failures; after the cooldown lets one probe through. */
    private static class CircuitBreaker {
        private final int failureThreshold;
        private final long cooldownMs;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openUntil = new AtomicLong();

        CircuitBreaker(int failureThreshold, long cooldownMs) {
            this.failureThreshold = failureThreshold;
            this.cooldownMs = cooldownMs;
        }

        boolean allow() {
            long until = openUntil.get();
            if (until == 0) {
                return true;
            }
            // half-open: exactly one caller wins the CAS and probes
            return System.currentTimeMillis() >= until
                    && openUntil.compareAndSet(until, System.currentTimeMillis() + cooldownMs);
        }

        void success() {
            consecutiveFailures.set(0);
            openUntil.set(0);
        }

        void failure() {
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                openUntil.set(System.currentTimeMillis() + cooldownMs);
            }
        }

        boolean isOpen() {
            return openUntil.get() > System.currentTimeMillis();
        }
    }
}
