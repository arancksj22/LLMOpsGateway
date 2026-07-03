package com.example.llmgateway.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Zero-dependency local embedding: hashed bag of content words + word bigrams
 * (feature hashing with a sign trick), L2-normalized. Not a neural embedding,
 * but robust enough to match reworded queries that share content words —
 * good for offline demos and load tests. Swap to the Gemini embedding
 * provider for true semantic similarity.
 */
public class HashingEmbeddingService implements EmbeddingService {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
            "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
            "shall", "should", "may", "might", "must", "of", "in", "on", "at", "to",
            "for", "with", "by", "from", "about", "as", "into", "and", "or", "but",
            "not", "no", "so", "if", "then", "than", "that", "this", "these", "those",
            "it", "its", "i", "you", "he", "she", "we", "they", "me", "my", "your",
            "his", "her", "our", "their", "what", "which", "who", "whom", "how",
            "please", "kindly", "just", "really", "very", "there", "here", "them", "us");

    private final int dim;

    public HashingEmbeddingService(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        List<String> tokens = tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            add(v, tokens.get(i), 1.0f);
            if (i + 1 < tokens.size()) {
                add(v, tokens.get(i) + "_" + tokens.get(i + 1), 0.5f);
            }
        }
        normalize(v);
        return v;
    }

    @Override
    public int dimension() {
        return dim;
    }

    private List<String> tokenize(String text) {
        String norm = text.toLowerCase().replaceAll("[^a-z0-9 ]", " ");
        List<String> out = new ArrayList<>();
        for (String w : norm.split("\\s+")) {
            if (!w.isBlank() && !STOPWORDS.contains(w)) {
                out.add(stem(w));
            }
        }
        return out;
    }

    /** Crude suffix stemming so "explaining"/"explains"/"explain" collide. */
    private String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private void add(float[] v, String token, float weight) {
        int h = mix(token.hashCode());
        int idx = Math.floorMod(h, dim);
        float sign = ((h >> 16) & 1) == 0 ? 1f : -1f;
        v[idx] += sign * weight;
    }

    private int mix(int h) {
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private void normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        if (n == 0) return;
        float inv = (float) (1.0 / Math.sqrt(n));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }
}
