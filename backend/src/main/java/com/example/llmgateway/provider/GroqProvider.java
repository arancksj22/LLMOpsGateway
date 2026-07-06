package com.example.llmgateway.provider;

import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.config.GatewayProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Groq — OpenAI-compatible API, free tier, real SSE streaming. */
@Component
public class GroqProvider implements LlmProvider {

    private final RestClient rest;
    private final GatewayProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public GroqProvider(RestClient rest, GatewayProperties props, ObjectMapper mapper) {
        this.rest = rest;
        this.props = props;
        this.mapper = mapper;
    }

    private GatewayProperties.Groq cfg() {
        return props.providers().groq();
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public boolean configured() {
        return !cfg().apiKey().isBlank();
    }

    @Override
    public ProviderResult complete(List<Message> messages, double temperature, Integer maxTokens) {
        JsonNode resp = rest.post()
                .uri(cfg().baseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg().apiKey())
                .body(body(messages, temperature, maxTokens, false))
                .retrieve()
                .body(JsonNode.class);
        String content = resp.path("choices").path(0).path("message").path("content").asText("");
        return new ProviderResult(content,
                resp.path("model").asText(cfg().model()),
                name(),
                resp.path("usage").path("prompt_tokens").asInt(0),
                resp.path("usage").path("completion_tokens").asInt(0));
    }

    /** Real streaming: forward each SSE delta as it arrives, keep the full text for caching. */
    @Override
    public ProviderResult stream(List<Message> messages, double temperature, Integer maxTokens, Consumer<String> onDelta) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg().baseUrl() + "/chat/completions"))
                    .header("Authorization", "Bearer " + cfg().apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body(messages, temperature, maxTokens, true))))
                    .build();
            HttpResponse<java.util.stream.Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Groq stream HTTP " + resp.statusCode());
            }
            StringBuilder full = new StringBuilder();
            int[] usage = {0, 0};
            resp.body().forEach(line -> {
                if (!line.startsWith("data: ") || line.contains("[DONE]")) {
                    return;
                }
                JsonNode node = mapper.readTree(line.substring(6));
                String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    full.append(delta);
                    onDelta.accept(delta);
                }
                JsonNode u = node.path("x_groq").path("usage");
                if (!u.isMissingNode()) {
                    usage[0] = u.path("prompt_tokens").asInt(0);
                    usage[1] = u.path("completion_tokens").asInt(0);
                }
            });
            return new ProviderResult(full.toString(), cfg().model(), name(), usage[0], usage[1]);
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("Groq stream failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> body(List<Message> messages, double temperature, Integer maxTokens, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", cfg().model());
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        body.put("temperature", temperature);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }
}
