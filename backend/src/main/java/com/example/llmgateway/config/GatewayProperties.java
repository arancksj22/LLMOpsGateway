package com.example.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/** All gateway tuning lives here (bound from application.yml / env vars) — nothing is hardcoded. */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue("admin-secret") String adminKey,
        @DefaultValue("gw_demo") String demoKey,
        @DefaultValue("local") String instanceId,
        @DefaultValue ExactCache exactCache,
        @DefaultValue SemanticCache semanticCache,
        @DefaultValue Compression compression,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Budget budget,
        @DefaultValue Backpressure backpressure,
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Embedding embedding,
        @DefaultValue Qdrant qdrant,
        @DefaultValue Providers providers,
        Map<String, Pricing> pricing) {

    public GatewayProperties {
        pricing = pricing == null ? Map.of() : pricing;
    }

    public record ExactCache(@DefaultValue("true") boolean enabled,
                             @DefaultValue("3600") long ttlSeconds) {
    }

    public record SemanticCache(@DefaultValue("true") boolean enabled,
                                @DefaultValue("0.66") double threshold,
                                @DefaultValue("3600") long ttlSeconds,
                                @DefaultValue("0.7") double maxTemperature) {
    }

    public record Compression(@DefaultValue("true") boolean enabled,
                              @DefaultValue("40") int minWords) {
    }

    public record RateLimit(@DefaultValue("120") int defaultRpm) {
    }

    public record Budget(@DefaultValue("25") double orgMonthlyUsd,
                         @DefaultValue("10") double defaultKeyMonthlyUsd,
                         @DefaultValue("true") boolean enforce) {
    }

    public record Backpressure(@DefaultValue("64") int maxConcurrent) {
    }

    public record CircuitBreaker(@DefaultValue("3") int failureThreshold,
                                 @DefaultValue("30") int cooldownSeconds) {
    }

    /** provider = "hash" (local, zero-dep) or "gemini" (free hosted API, true semantic vectors). */
    public record Embedding(@DefaultValue("hash") String provider,
                            @DefaultValue("384") int dimension) {
    }

    public record Qdrant(@DefaultValue("http://localhost:6333") String url,
                         @DefaultValue("semantic_cache") String collection) {
    }

    public record Providers(@DefaultValue({"groq", "gemini", "mock"}) List<String> order,
                            @DefaultValue Groq groq,
                            @DefaultValue Gemini gemini,
                            @DefaultValue Mock mock) {
    }

    public record Groq(@DefaultValue("") String apiKey,
                       @DefaultValue("https://api.groq.com/openai/v1") String baseUrl,
                       @DefaultValue("llama-3.1-8b-instant") String model) {
    }

    public record Gemini(@DefaultValue("") String apiKey,
                         @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
                         @DefaultValue("gemini-2.0-flash") String model,
                         @DefaultValue("text-embedding-004") String embeddingModel) {
    }

    public record Mock(@DefaultValue("200") long latencyMs,
                       @DefaultValue("mock-model") String model) {
    }

    /** USD per 1M input / output tokens. */
    public record Pricing(@DefaultValue("0.1") double input,
                          @DefaultValue("0.4") double output) {
    }
}
