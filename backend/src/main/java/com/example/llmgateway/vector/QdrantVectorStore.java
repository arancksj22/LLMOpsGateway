package com.example.llmgateway.vector;

import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.embedding.EmbeddingService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal Qdrant integration over its REST API — no client library needed. */
@Component
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final RestClient rest;
    private final GatewayProperties props;
    private final EmbeddingService embedding;
    private final ObjectMapper mapper;
    private volatile boolean collectionReady = false;

    public QdrantVectorStore(RestClient rest, GatewayProperties props, EmbeddingService embedding, ObjectMapper mapper) {
        this.rest = rest;
        this.props = props;
        this.embedding = embedding;
        this.mapper = mapper;
    }

    private String base() {
        return props.getQdrant().getUrl() + "/collections/" + props.getQdrant().getCollection();
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
                collectionReady = true;
            } catch (Exception notFound) {
                rest.put()
                        .uri(base())
                        .body(Map.of("vectors", Map.of("size", embedding.dimension(), "distance", "Cosine")))
                        .retrieve()
                        .body(JsonNode.class);
                collectionReady = true;
                log.info("Created Qdrant collection '{}' (dim={})", props.getQdrant().getCollection(), embedding.dimension());
            }
        }
    }

    @Override
    public void upsert(String id, float[] vector, Map<String, Object> payload) {
        ensureCollection();
        Map<String, Object> point = new HashMap<>();
        point.put("id", id);
        point.put("vector", toList(vector));
        point.put("payload", payload);
        rest.put()
                .uri(base() + "/points?wait=true")
                .body(Map.of("points", List.of(point)))
                .retrieve()
                .body(JsonNode.class);
    }

    @Override
    public Optional<Hit> searchTop1(float[] vector) {
        ensureCollection();
        JsonNode resp = rest.post()
                .uri(base() + "/points/search")
                .body(Map.of("vector", toList(vector), "limit", 1, "with_payload", true))
                .retrieve()
                .body(JsonNode.class);
        JsonNode result = resp.path("result");
        if (!result.isArray() || result.isEmpty()) {
            return Optional.empty();
        }
        JsonNode top = result.get(0);
        double score = top.path("score").asDouble();
        Map<String, Object> payload = mapper.convertValue(top.path("payload"), Map.class);
        return Optional.of(new Hit(score, payload));
    }

    private List<Float> toList(float[] v) {
        Float[] boxed = new Float[v.length];
        for (int i = 0; i < v.length; i++) {
            boxed[i] = v[i];
        }
        return List.of(boxed);
    }
}
