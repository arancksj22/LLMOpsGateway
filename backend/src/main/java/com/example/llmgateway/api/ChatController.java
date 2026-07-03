package com.example.llmgateway.api;

import com.example.llmgateway.api.dto.ChatRequest;
import com.example.llmgateway.api.dto.ChatResponse;
import com.example.llmgateway.auth.ApiKeyFilter;
import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.service.ChatService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** OpenAI-compatible chat completions endpoint (JSON or SSE streaming). */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final ExecutorService sseExecutor;
    private final ObjectMapper mapper;

    public ChatController(ChatService chatService, ExecutorService sseExecutor, ObjectMapper mapper) {
        this.chatService = chatService;
        this.sseExecutor = sseExecutor;
        this.mapper = mapper;
    }

    @PostMapping(value = "/v1/chat/completions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@RequestBody ChatRequest req, HttpServletRequest http) {
        ApiKeyInfo key = (ApiKeyInfo) http.getAttribute(ApiKeyFilter.ATTR);
        if (Boolean.TRUE.equals(req.stream())) {
            return stream(req, key);
        }
        return chatService.chat(req, key);
    }

    private SseEmitter stream(ChatRequest req, ApiKeyInfo key) {
        SseEmitter emitter = new SseEmitter(180_000L);
        String id = "chatcmpl-" + UUID.randomUUID();
        sseExecutor.execute(() -> {
            try {
                ChatResponse full = chatService.chatStream(req, key,
                        delta -> emit(emitter, chunk(id, delta, null)));
                emit(emitter, chunk(id, null, "stop"));
                // final event carries usage + gateway metadata for observability
                emitter.send(SseEmitter.event().name("gateway").data(mapper.writeValueAsString(
                        Map.of("usage", full.usage(), "gateway", full.gateway()))));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (GatewayException e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            "{\"error\":{\"type\":\"" + e.getType() + "\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}}"));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private String chunk(String id, String delta, String finishReason) {
        try {
            Map<String, Object> deltaMap = delta == null ? Map.of() : Map.of("role", "assistant", "content", delta);
            return mapper.writeValueAsString(Map.of(
                    "id", id,
                    "object", "chat.completion.chunk",
                    "created", System.currentTimeMillis() / 1000,
                    "choices", List.of(new java.util.HashMap<>() {{
                        put("index", 0);
                        put("delta", deltaMap);
                        put("finish_reason", finishReason);
                    }})));
        } catch (Exception e) {
            return "{}";
        }
    }

    private void emit(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().data(json));
        } catch (Exception e) {
            throw new RuntimeException("client disconnected", e);
        }
    }
}
