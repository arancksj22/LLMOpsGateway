package com.example.llmgateway.embedding;

import com.example.llmgateway.config.GatewayProperties;
import tools.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Free hosted embeddings via Google's text-embedding-004 (768-dim). */
public class GeminiEmbeddingService implements EmbeddingService {

    private final RestClient rest;
    private final GatewayProperties props;

    public GeminiEmbeddingService(RestClient rest, GatewayProperties props) {
        this.rest = rest;
        this.props = props;
    }

    @Override
    public float[] embed(String text) {
        GatewayProperties.Gemini g = props.getProviders().getGemini();
        String url = g.getBaseUrl() + "/models/" + g.getEmbeddingModel() + ":embedContent?key=" + g.getApiKey();
        JsonNode resp = rest.post()
                .uri(url)
                .body(Map.of("content", Map.of("parts", List.of(Map.of("text", text)))))
                .retrieve()
                .body(JsonNode.class);
        JsonNode values = resp.path("embedding").path("values");
        float[] v = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            v[i] = (float) values.get(i).asDouble();
        }
        return v;
    }

    @Override
    public int dimension() {
        return 768;
    }
}
