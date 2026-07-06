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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** OpenAI-compatible chat completions endpoint (JSON, or SSE when stream=true). */
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

    /**
     * SSE streaming passthrough: deltas are forwarded to the client as they
     * arrive (or replayed from cache) in OpenAI chunk format, while the full
     * response is captured for caching, cost accounting and metrics.
     */
    private SseEmitter stream(ChatRequest req, ApiKeyInfo key) {
        SseEmitter emitter = new SseEmitter(180_000L);
        String id = "chatcmpl-" + UUID.randomUUID();
        sseExecutor.execute(() -> {
            try {
                ChatResponse full = chatService.chatStream(req, key,
                        delta -> emit(emitter, chunk(id, delta, null)));
                emit(emitter, chunk(id, null, "stop"));
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
        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta == null ? Map.of() : Map.of("role", "assistant", "content", delta));
        choice.put("finish_reason", finishReason);
        return mapper.writeValueAsString(Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", System.currentTimeMillis() / 1000,
                "choices", List.of(choice)));
    }

    private void emit(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().data(json));
        } catch (Exception e) {
            throw new RuntimeException("client disconnected", e);
        }
    }
}
