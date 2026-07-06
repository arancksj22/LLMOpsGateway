package com.example.llmgateway.cache;

import com.example.llmgateway.config.GatewayProperties;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Exact-match cache — byte-identical request deduplication. The whole
 * canonical request (messages + temperature) is SHA-256 hashed and the stored
 * response lives in Redis, so a response cached by one gateway instance is
 * served by any other. Checked before the (more expensive) semantic cache.
 */
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
        if (!props.exactCache().enabled()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get("cache:exact:" + hash);
        return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, CachedResponse.class));
    }

    public void put(String hash, CachedResponse response) {
        if (props.exactCache().enabled()) {
            redis.opsForValue().set("cache:exact:" + hash, mapper.writeValueAsString(response),
                    Duration.ofSeconds(props.exactCache().ttlSeconds()));
        }
    }
}
