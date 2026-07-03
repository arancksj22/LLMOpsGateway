package com.example.llmgateway.cache;

import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.embedding.EmbeddingService;
import com.example.llmgateway.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Meaning-equivalent request caching: embed the prompt, search the shared
 * vector DB, and serve the stored answer when similarity clears the
 * configured threshold. Best-effort: any embedding/vector failure just
 * degrades to a cache miss.
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final EmbeddingService embedding;
    private final VectorStore vectorStore;
    private final GatewayProperties props;

    public SemanticCacheService(EmbeddingService embedding, VectorStore vectorStore, GatewayProperties props) {
        this.embedding = embedding;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    public boolean enabled() {
        return props.getSemanticCache().isEnabled();
    }

    /** Embeds once so lookup + store don't compute the vector twice. */
    public float[] embed(String text) {
        try {
            return embedding.embed(text);
        } catch (Exception e) {
            log.warn("Embedding failed: {}", e.getMessage());
            return null;
        }
    }

    public double similarity(String a, String b) {
        return EmbeddingService.cosine(embedding.embed(a), embedding.embed(b));
    }

    public Optional<CachedResponse> lookup(float[] vector) {
        if (!enabled() || vector == null) {
            return Optional.empty();
        }
        try {
            Optional<VectorStore.Hit> hit = vectorStore.searchTop1(vector);
            if (hit.isEmpty() || hit.get().score() < props.getSemanticCache().getThreshold()) {
                return Optional.empty();
            }
            Map<String, Object> p = hit.get().payload();
            long ts = ((Number) p.getOrDefault("ts", 0L)).longValue();
            if (System.currentTimeMillis() - ts > props.getSemanticCache().getTtlSeconds() * 1000L) {
                return Optional.empty();
            }
            return Optional.of(new CachedResponse(
                    (String) p.getOrDefault("content", ""),
                    (String) p.getOrDefault("model", "unknown"),
                    (String) p.getOrDefault("provider", "unknown"),
                    ((Number) p.getOrDefault("prompt_tokens", 0)).intValue(),
                    ((Number) p.getOrDefault("completion_tokens", 0)).intValue(),
                    ts));
        } catch (Exception e) {
            log.warn("Semantic lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void store(String hash, float[] vector, CachedResponse r) {
        if (!enabled() || vector == null) {
            return;
        }
        try {
            String id = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();
            vectorStore.upsert(id, vector, Map.of(
                    "content", r.content(),
                    "model", r.model(),
                    "provider", r.provider(),
                    "prompt_tokens", r.promptTokens(),
                    "completion_tokens", r.completionTokens(),
                    "ts", r.ts()));
        } catch (Exception e) {
            log.warn("Semantic store failed: {}", e.getMessage());
        }
    }
}
