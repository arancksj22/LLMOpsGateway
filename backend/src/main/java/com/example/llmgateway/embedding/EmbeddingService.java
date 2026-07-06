package com.example.llmgateway.embedding;

import com.example.llmgateway.config.GatewayProperties;
import tools.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns text into a vector for semantic-cache similarity search. Two modes,
 * picked by config: "hash" — local feature-hashing of content words and
 * bigrams (free, offline, good enough to match rewordings that share content
 * words) — or "gemini" — Google's free text-embedding-004 API (true semantic
 * vectors, 768-dim).
 */
public class EmbeddingService {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
            "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
            "shall", "should", "may", "might", "must", "of", "in", "on", "at", "to",
            "for", "with", "by", "from", "about", "as", "into", "and", "or", "but",
            "not", "no", "so", "if", "then", "than", "that", "this", "these", "those",
            "it", "its", "i", "you", "he", "she", "we", "they", "me", "my", "your",
            "his", "her", "our", "their", "what", "which", "who", "whom", "how",
            "please", "kindly", "just", "really", "very", "there", "here", "them", "us");

    private final GatewayProperties.Embedding cfg;
    private final GatewayProperties.Gemini gemini;
    private final RestClient rest;

    public EmbeddingService(GatewayProperties.Embedding cfg, GatewayProperties.Gemini gemini, RestClient rest) {
        this.cfg = cfg;
        this.gemini = gemini;
        this.rest = rest;
    }

    private boolean useGemini() {
        return "gemini".equalsIgnoreCase(cfg.provider());
    }

    public int dimension() {
        return useGemini() ? 768 : cfg.dimension();
    }

    public float[] embed(String text) {
        return useGemini() ? embedGemini(text) : embedHash(text);
    }

    public double similarity(String a, String b) {
        return cosine(embed(a), embed(b));
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private float[] embedGemini(String text) {
        String url = gemini.baseUrl() + "/models/" + gemini.embeddingModel() + ":embedContent?key=" + gemini.apiKey();
        JsonNode values = rest.post()
                .uri(url)
                .body(Map.of("content", Map.of("parts", List.of(Map.of("text", text)))))
                .retrieve()
                .body(JsonNode.class)
                .path("embedding").path("values");
        float[] v = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            v[i] = (float) values.get(i).asDouble();
        }
        return v;
    }

    // Feature hashing: each content word (crudely stemmed) and word bigram is
    // hashed into one of `dimension` buckets with a +/- sign, then the vector
    // is L2-normalized so cosine similarity is meaningful.
    private float[] embedHash(String text) {
        float[] v = new float[cfg.dimension()];
        List<String> tokens = new ArrayList<>();
        for (String w : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (!w.isBlank() && !STOPWORDS.contains(w)) {
                tokens.add(stem(w));
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            add(v, tokens.get(i), 1.0f);
            if (i + 1 < tokens.size()) {
                add(v, tokens.get(i) + "_" + tokens.get(i + 1), 0.5f);
            }
        }
        double n = 0;
        for (float x : v) n += x * x;
        if (n > 0) {
            float inv = (float) (1.0 / Math.sqrt(n));
            for (int i = 0; i < v.length; i++) v[i] *= inv;
        }
        return v;
    }

    /** Crude suffix stemming so "explaining"/"explains"/"explain" collide. */
    private String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private void add(float[] v, String token, float weight) {
        int h = token.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        v[Math.floorMod(h, v.length)] += (((h >> 16) & 1) == 0 ? 1f : -1f) * weight;
    }
}
