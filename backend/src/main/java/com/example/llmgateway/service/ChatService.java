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
import com.example.llmgateway.concurrency.SingleFlight;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.cost.BudgetService;
import com.example.llmgateway.metrics.GatewayMetrics;
import com.example.llmgateway.provider.ProviderResult;
import com.example.llmgateway.provider.ProviderRouter;
import com.example.llmgateway.ratelimit.RateLimiterService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * The whole request pipeline, in order: rate limit -> backpressure ->
 * compress -> exact cache -> semantic cache -> budget check -> single-flight
 * -> provider failover -> store caches, record spend, publish metrics.
 * "First try to skip the call; if we must call, make it cheap; and always
 * control and observe it."
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
    private final SingleFlight singleFlight;
    private final GatewayMetrics metrics;
    // Backpressure: bound in-flight work per instance; when full, reject with
    // a clean 503 immediately instead of queueing until the instance collapses.
    private final Semaphore inFlight;

    public ChatService(GatewayProperties props, PromptCompressor compressor, ExactCacheService exactCache,
                       SemanticCacheService semanticCache, ProviderRouter router, RateLimiterService rateLimiter,
                       BudgetService budget, SingleFlight singleFlight, GatewayMetrics metrics) {
        this.props = props;
        this.compressor = compressor;
        this.exactCache = exactCache;
        this.semanticCache = semanticCache;
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.singleFlight = singleFlight;
        this.metrics = metrics;
        this.inFlight = new Semaphore(props.backpressure().maxConcurrent());
    }

    /** Non-streaming entry point. `onDelta` variant below streams the same pipeline. */
    public ChatResponse chat(ChatRequest req, ApiKeyInfo key) {
        return chatStream(req, key, null);
    }

    public ChatResponse chatStream(ChatRequest req, ApiKeyInfo key, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        if (req.messages() == null || req.messages().isEmpty()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "messages is required");
        }
        rateLimiter.check(key);
        if (!inFlight.tryAcquire()) {
            metrics.rejection("backpressure");
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "overloaded",
                    "Gateway is at capacity, retry shortly");
        }
        try {
            return pipeline(req, key, start, onDelta);
        } finally {
            inFlight.release();
        }
    }

    private ChatResponse pipeline(ChatRequest req, ApiKeyInfo key, long start, Consumer<String> onDelta) {
        boolean cacheWanted = req.cache() == null || req.cache();
        boolean compressWanted = req.compress() != null ? req.compress() : props.compression().enabled();
        double temperature = req.temperature() == null ? 0.0 : req.temperature();

        PromptCompressor.Result comp = compressWanted ? compressor.compress(req.messages()) : compressor.none(req.messages());
        List<Message> messages = comp.messages();
        if (comp.applied()) {
            metrics.compressionSaved(comp.tokensSaved());
        }

        String hash = sha256(canonical(messages, temperature));
        // cacheability policy: high-temperature (creative) requests bypass the caches
        boolean cacheable = cacheWanted && temperature <= props.semanticCache().maxTemperature();

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

        final float[] vec = vector;
        if (onDelta != null) {
            // streaming miss: stream straight through, then cache (no coalescing — keep it simple)
            ProviderResult r = router.stream(messages, temperature, req.maxTokens(), onDelta);
            return respond(store(r, hash, vec, cacheable, key), "miss", key, comp, start, false, null);
        }

        SingleFlight.Outcome out = cacheable
                ? singleFlight.run(hash, () ->
                        store(router.complete(messages, temperature, req.maxTokens()), hash, vec, true, key))
                : new SingleFlight.Outcome(
                        store(router.complete(messages, temperature, req.maxTokens()), hash, null, false, key), false);
        if (out.coalesced()) {
            metrics.coalesced();
        }
        return respond(out.response(), "miss", key, comp, start, out.coalesced(), onDelta);
    }

    /** Leader-side bookkeeping after a real provider call: cache stores, spend, token stats. */
    private CachedResponse store(ProviderResult r, String hash, float[] vector, boolean cacheable, ApiKeyInfo key) {
        CachedResponse cached = new CachedResponse(r.content(), r.model(), r.provider(),
                r.promptTokens(), r.completionTokens(), System.currentTimeMillis());
        if (cacheable && !r.content().isBlank()) {
            exactCache.put(hash, cached);
            semanticCache.store(hash, vector, cached);
        }
        double usd = budget.cost(r.model(), r.promptTokens(), r.completionTokens());
        budget.record(key, usd);
        metrics.usage(key.team(), r.promptTokens(), r.completionTokens(), usd);
        return cached;
    }

    private ChatResponse respond(CachedResponse r, String cacheStatus, ApiKeyInfo key,
                                 PromptCompressor.Result comp, long start, boolean coalesced,
                                 Consumer<String> onDelta) {
        long latency = System.currentTimeMillis() - start;
        double callCost = budget.cost(r.model(), r.promptTokens(), r.completionTokens());
        boolean hit = !"miss".equals(cacheStatus);
        if (hit) {
            metrics.saved(callCost, r.promptTokens() + r.completionTokens());
        }
        metrics.request(cacheStatus, key.team(), latency);

        if (onDelta != null && hit) {
            // cache hit on a streaming request: replay the stored content in chunks
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
                new ChatResponse.GatewayInfo(cacheStatus, r.provider(), props.instanceId(), latency,
                        round6(hit ? 0 : callCost), round6(hit ? callCost : 0),
                        comp.applied(), comp.tokensSaved(), coalesced));
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
            sb.append(m.role()).append(':').append(m.content()).append('');
        }
        return sb.append("temp=").append(temperature).toString();
    }

    private String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
