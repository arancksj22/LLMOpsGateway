package com.example.llmgateway.provider;

import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.config.GatewayProperties;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Google Gemini — free tier; non-streaming (fake-streamed via the interface default). */
@Component
public class GeminiProvider implements LlmProvider {

    private final RestClient rest;
    private final GatewayProperties props;

    public GeminiProvider(RestClient rest, GatewayProperties props) {
        this.rest = rest;
        this.props = props;
    }

    private GatewayProperties.Gemini cfg() {
        return props.getProviders().getGemini();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public boolean configured() {
        return cfg().getApiKey() != null && !cfg().getApiKey().isBlank();
    }

    @Override
    public ProviderResult complete(List<Message> messages, double temperature, Integer maxTokens) {
        List<Map<String, Object>> contents = new ArrayList<>();
        StringBuilder system = new StringBuilder();
        for (Message m : messages) {
            if ("system".equals(m.role())) {
                system.append(m.content()).append('\n');
            } else {
                String role = "assistant".equals(m.role()) ? "model" : "user";
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", m.content()))));
            }
        }
        if (contents.isEmpty()) {
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", ""))));
        }
        Map<String, Object> genConfig = new HashMap<>();
        genConfig.put("temperature", temperature);
        if (maxTokens != null) {
            genConfig.put("maxOutputTokens", maxTokens);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("contents", contents);
        body.put("generationConfig", genConfig);
        if (!system.isEmpty()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system.toString()))));
        }
        String url = cfg().getBaseUrl() + "/models/" + cfg().getModel() + ":generateContent?key=" + cfg().getApiKey();
        JsonNode resp = rest.post().uri(url).body(body).retrieve().body(JsonNode.class);
        String content = resp.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        JsonNode usage = resp.path("usageMetadata");
        return new ProviderResult(content,
                cfg().getModel(),
                name(),
                usage.path("promptTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(LlmProvider.estimateTokens(content)));
    }
}
