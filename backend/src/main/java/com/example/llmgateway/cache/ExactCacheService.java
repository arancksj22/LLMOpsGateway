package com.example.llmgateway.cache;

import com.example.llmgateway.config.GatewayProperties;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/** Byte-identical request deduplication, shared across all instances via Redis. */
@Service
public class ExactCacheService {

    private final StringRedisTemplate redis;
    private final GatewayProperties props;
    private final ObjectMapper mapper;

    public ExactCacheService(StringRedisTemplate redis, GatewayProperties props, ObjectMapper mapper) {
        this.redis = redis;
        this.props = props;
        this.mapper = mapper;
    }

    public Optional<CachedResponse> get(String hash) {
        if (!props.getExactCache().isEnabled()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get("cache:exact:" + hash);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, CachedResponse.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void put(String hash, CachedResponse response) {
        if (!props.getExactCache().isEnabled()) {
            return;
        }
        try {
            redis.opsForValue().set("cache:exact:" + hash, mapper.writeValueAsString(response),
                    Duration.ofSeconds(props.getExactCache().getTtlSeconds()));
        } catch (Exception ignored) {
        }
    }
}
