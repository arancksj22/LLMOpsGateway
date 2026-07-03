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
import java.util.function.Consumer;

/** Ordered multi-provider failover with per-provider circuit breaking. */
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
                    props.getCircuitBreaker().getFailureThreshold(),
                    props.getCircuitBreaker().getCooldownSeconds() * 1000L));
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
        for (String name : props.getProviders().getOrder()) {
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
        for (String name : props.getProviders().getOrder()) {
            LlmProvider p = providers.get(name.trim());
            if (p != null) {
                out.put(p.name(), Map.of(
                        "configured", p.configured(),
                        "circuit_open", breakers.get(p.name()).isOpen()));
            }
        }
        return out;
    }
}
