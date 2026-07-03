package com.example.llmgateway.service;

import com.example.llmgateway.api.GatewayException;
import com.example.llmgateway.api.dto.ChatRequest;
import com.example.llmgateway.api.dto.ChatResponse;
import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.cache.CachedResponse;
import com.example.llmgateway.cache.ExactCacheService;
import com.example.llmgateway.cache.SemanticCacheService;
import com.example.llmgateway.compress.PromptCompressor;
import com.example.llmgateway.concurrency.Backpressure;
import com.example.llmgateway.concurrency.SingleFlight;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.cost.BudgetService;
import com.example.llmgateway.cost.CostService;
import com.example.llmgateway.metrics.GatewayMetrics;
import com.example.llmgateway.metrics.StatsService;
import com.example.llmgateway.provider.ProviderResult;
import com.example.llmgateway.provider.ProviderRouter;
import com.example.llmgateway.ratelimit.RateLimiterService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The request pipeline: rate limit -> backpressure -> compress -> exact cache
 * -> semantic cache -> budget check -> single-flight -> provider failover ->
 * store caches + record spend + metrics.
 */
@Service
public class ChatService {

    private final GatewayProperties props;
    private final PromptCompressor compressor;
    private final ExactCacheService exactCache;
    private final SemanticCacheService semanticCache;
    private final ProviderRouter router;
    private final RateLimiterService rateLimiter;
    private final BudgetService budget;
    private final CostService cost;
    private final SingleFlight singleFlight;
    private final Backpressure backpressure;
    private final GatewayMetrics metrics;
    private final StatsService stats;

    public ChatService(GatewayProperties props, PromptCompressor compressor, ExactCacheService exactCache,
                       SemanticCacheService semanticCache, ProviderRouter router, RateLimiterService rateLimiter,
                       BudgetService budget, CostService cost, SingleFlight singleFlight,
                       Backpressure backpressure, GatewayMetrics metrics, StatsService stats) {
        this.props = props;
        this.compressor = compressor;
        this.exactCache = exactCache;
        this.semanticCache = semanticCache;
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.cost = cost;
        this.singleFlight = singleFlight;
        this.backpressure = backpressure;
        this.metrics = metrics;
        this.stats = stats;
    }

    public ChatResponse chat(ChatRequest req, ApiKeyInfo key) {
        long start = System.currentTimeMillis();
        validate(req);
        rateLimiter.check(key);
        acquireOrReject();
        try {
            return pipeline(req, key, start, null);
        } finally {
            backpressure.release();
        }
    }

    /**
     * Streaming variant: cache hits and coalesced results are re-chunked to
     * the consumer; real misses stream straight from the provider while the
     * full text is captured for caching/metrics.
     */
    public ChatResponse chatStream(ChatRequest req, ApiKeyInfo key, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        validate(req);
        rateLimiter.check(key);
        acquireOrReject();
        try {
            return pipeline(req, key, start, onDelta);
        } finally {
            backpressure.release();
        }
    }

    private void validate(ChatRequest req) {
        if (req.messages() == null || req.messages().isEmpty()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "messages is required");
        }
    }

    private void acquireOrReject() {
        if (!backpressure.tryAcquire()) {
            metrics.rejection("backpressure");
            stats.incr("rejected_backpressure");
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "overloaded",
                    "Gateway is at capacity, retry shortly");
        }
    }

    private ChatResponse pipeline(ChatRequest req, ApiKeyInfo key, long start, Consumer<String> onDelta) {
        boolean cacheWanted = req.cache() == null || req.cache();
        boolean compressWanted = req.compress() != null ? req.compress() : props.getCompression().isEnabled();
        double temperature = req.temperature() == null ? 0.0 : req.temperature();

        PromptCompressor.Result comp = compressWanted ? compressor.compress(req.messages()) : compressor.none(req.messages());
        List<Message> messages = comp.messages();
        if (comp.applied()) {
            metrics.compressionSaved(comp.tokensSaved());
            stats.incrBy("tokens_saved_compression", comp.tokensSaved());
        }

        String hash = sha256(canonical(messages, temperature));
        // cacheability policy: high-temperature (creative) requests bypass the caches
        boolean cacheable = cacheWanted && temperature <= props.getSemanticCache().getMaxTemperature();

        float[] vector = null;
        if (cacheable) {
            Optional<CachedResponse> exact = exactCache.get(hash);
            if (exact.isPresent()) {
                return respond(exact.get(), "exact", key, comp, start, false, onDelta);
            }
            if (semanticCache.enabled()) {
                vector = semanticCache.embed(promptText(messages));
                Optional<CachedResponse> semantic = semanticCache.lookup(vector);
                if (semantic.isPresent()) {
                    return respond(semantic.get(), "semantic", key, comp, start, false, onDelta);
                }
            }
        }

        budget.check(key);

        if (onDelta != null) {
            // streaming miss: stream straight through, then cache (no coalescing — keep it simple)
            final float[] vec = vector;
            ProviderResult r = router.stream(messages, temperature, req.maxTokens(), onDelta);
            CachedResponse fresh = store(r, hash, vec, cacheable, key);
            return respond(fresh, "miss", key, comp, start, false, null);
        }

        final float[] vec = vector;
        SingleFlight.Outcome out = cacheable
                ? singleFlight.run(hash, () -> {
                    ProviderResult r = router.complete(messages, temperature, req.maxTokens());
                    return store(r, hash, vec, true, key);
                })
                : new SingleFlight.Outcome(store(router.complete(messages, temperature, req.maxTokens()),
                        hash, null, false, key), false);

        if (out.coalesced()) {
            metrics.coalesced();
            stats.incr("coalesced");
        }
        return respond(out.response(), "miss", key, comp, start, out.coalesced(), onDelta);
    }

    /** Leader-side bookkeeping: cache stores, spend recording, token stats. */
    private CachedResponse store(ProviderResult r, String hash, float[] vector, boolean cacheable, ApiKeyInfo key) {
        CachedResponse cached = new CachedResponse(r.content(), r.model(), r.provider(),
                r.promptTokens(), r.completionTokens(), System.currentTimeMillis());
        if (cacheable && !r.content().isBlank()) {
            exactCache.put(hash, cached);
            semanticCache.store(hash, vector, cached);
        }
        double usd = cost.cost(r.model(), r.promptTokens(), r.completionTokens());
        budget.record(key, usd);
        metrics.tokens(r.promptTokens(), r.completionTokens());
        metrics.cost(key.team(), usd);
        stats.incrBy("tokens_prompt", r.promptTokens());
        stats.incrBy("tokens_completion", r.completionTokens());
        stats.incrByFloat("cost_usd", usd);
        return cached;
    }

    private ChatResponse respond(CachedResponse r, String cacheStatus, ApiKeyInfo key,
                                 PromptCompressor.Result comp, long start, boolean coalesced,
                                 Consumer<String> onDelta) {
        long latency = System.currentTimeMillis() - start;
        double callCost = cost.cost(r.model(), r.promptTokens(), r.completionTokens());
        double costUsd = "miss".equals(cacheStatus) ? callCost : 0.0;
        double savedUsd = "miss".equals(cacheStatus) ? 0.0 : callCost;

        stats.incr("requests");
        switch (cacheStatus) {
            case "exact" -> stats.incr("exact_hits");
            case "semantic" -> stats.incr("semantic_hits");
            default -> stats.incr("misses");
        }
        if (savedUsd > 0) {
            stats.incrByFloat("saved_usd", savedUsd);
            stats.incrBy("tokens_saved_cache", r.promptTokens() + r.completionTokens());
            metrics.saved(savedUsd);
        }
        metrics.request(cacheStatus, key.team(), latency);

        if (onDelta != null && !"miss".equals(cacheStatus)) {
            // cache hit on a streaming request: replay stored content in chunks
            String content = r.content();
            for (int i = 0; i < content.length(); i += 48) {
                onDelta.accept(content.substring(i, Math.min(content.length(), i + 48)));
            }
        }

        return new ChatResponse(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                System.currentTimeMillis() / 1000,
                r.model(),
                List.of(new ChatResponse.Choice(0, new Message("assistant", r.content()), "stop")),
                new ChatResponse.Usage(r.promptTokens(), r.completionTokens(), r.promptTokens() + r.completionTokens()),
                new ChatResponse.GatewayInfo(cacheStatus, r.provider(), props.getInstanceId(), latency,
                        round6(costUsd), round6(savedUsd), comp.applied(), comp.tokensSaved(), coalesced));
    }

    private String promptText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.content()).append('\n');
        }
        return sb.toString();
    }

    private String canonical(List<Message> messages, double temperature) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.role()).append(':').append(m.content()).append("");
        }
        sb.append("temp=").append(temperature);
        return sb.toString();
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
