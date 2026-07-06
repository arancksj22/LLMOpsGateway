package com.example.llmgateway.cache;

import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.embedding.EmbeddingService;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic cache — the reason "Explain what X is" can be answered from a
 * response cached for "What is X?". Each fresh answer is stored in Qdrant
 * (shared by every gateway instance) under an embedding of its prompt; on
 * lookup we nearest-neighbour search and serve the stored answer when cosine
 * similarity clears the configured threshold and the entry isn't expired.
 * Talks to Qdrant's REST API directly — no client library.
 */
@Service
public class SemanticCacheService {

    private final EmbeddingService embedding;
    private final RestClient rest;
    private final GatewayProperties props;
    private volatile boolean collectionReady;

    public SemanticCacheService(EmbeddingService embedding, RestClient rest, GatewayProperties props) {
        this.embedding = embedding;
        this.rest = rest;
        this.props = props;
    }

    public boolean enabled() {
        return props.semanticCache().enabled();
    }

    /** Embed once per request so lookup + store don't compute the vector twice. */
    public float[] embed(String text) {
        return embedding.embed(text);
    }

    /** Used by /admin/similarity and the threshold-eval script. */
    public double similarity(String a, String b) {
        return embedding.similarity(a, b);
    }

    public Optional<CachedResponse> lookup(float[] vector) {
        if (!enabled()) {
            return Optional.empty();
        }
        ensureCollection();
        JsonNode result = rest.post()
                .uri(base() + "/points/search")
                .body(Map.of("vector", toList(vector), "limit", 1, "with_payload", true))
                .retrieve()
                .body(JsonNode.class)
                .path("result");
        if (result.isEmpty() || result.get(0).path("score").asDouble() < props.semanticCache().threshold()) {
            return Optional.empty();
        }
        JsonNode p = result.get(0).path("payload");
        long ts = p.path("ts").asLong();
        if (System.currentTimeMillis() - ts > props.semanticCache().ttlSeconds() * 1000L) {
            return Optional.empty();
        }
        return Optional.of(new CachedResponse(
                p.path("content").asText(),
                p.path("model").asText(),
                p.path("provider").asText(),
                p.path("prompt_tokens").asInt(),
                p.path("completion_tokens").asInt(),
                ts));
    }

    public void store(String hash, float[] vector, CachedResponse r) {
        if (!enabled()) {
            return;
        }
        ensureCollection();
        Map<String, Object> point = new HashMap<>();
        point.put("id", UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString());
        point.put("vector", toList(vector));
        point.put("payload", Map.of(
                "content", r.content(),
                "model", r.model(),
                "provider", r.provider(),
                "prompt_tokens", r.promptTokens(),
                "completion_tokens", r.completionTokens(),
                "ts", r.ts()));
        rest.put().uri(base() + "/points?wait=true").body(Map.of("points", List.of(point)))
                .retrieve().body(JsonNode.class);
    }

    private String base() {
        return props.qdrant().url() + "/collections/" + props.qdrant().collection();
    }

    private void ensureCollection() {
        if (collectionReady) {
            return;
        }
        synchronized (this) {
            if (collectionReady) {
                return;
            }
            try {
                rest.get().uri(base()).retrieve().body(JsonNode.class);
            } catch (Exception notFound) {
                rest.put().uri(base())
                        .body(Map.of("vectors", Map.of("size", embedding.dimension(), "distance", "Cosine")))
                        .retrieve().body(JsonNode.class);
            }
            collectionReady = true;
        }
    }

    private List<Float> toList(float[] v) {
        Float[] boxed = new Float[v.length];
        for (int i = 0; i < v.length; i++) {
            boxed[i] = v[i];
        }
        return List.of(boxed);
    }
}
