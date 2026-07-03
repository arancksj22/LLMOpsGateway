package com.example.llmgateway.vector;

import java.util.Map;
import java.util.Optional;

public interface VectorStore {

    void upsert(String id, float[] vector, Map<String, Object> payload);

    Optional<Hit> searchTop1(float[] vector);

    record Hit(double score, Map<String, Object> payload) {
    }
}
