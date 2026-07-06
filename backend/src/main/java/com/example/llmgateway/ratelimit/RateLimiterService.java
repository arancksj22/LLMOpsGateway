package com.example.llmgateway.ratelimit;

import com.example.llmgateway.api.GatewayException;
import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.metrics.GatewayMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Distributed rate limiting — the per-key requests/minute quota holds in
 * aggregate across ALL gateway instances, because the counter is a single
 * Redis key per (key, minute) incremented atomically by a Lua script. This
 * is what a per-app library can't do: it protects the org's shared provider
 * rate limit no matter which instance traffic lands on.
 */
@Service
public class RateLimiterService {

    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], 120) end " +
            "return c", Long.class);

    private final StringRedisTemplate redis;
    private final GatewayProperties props;
    private final GatewayMetrics metrics;

    public RateLimiterService(StringRedisTemplate redis, GatewayProperties props, GatewayMetrics metrics) {
        this.redis = redis;
        this.props = props;
        this.metrics = metrics;
    }

    public void check(ApiKeyInfo key) {
        if (key.rpm() == 0 && "demo".equals(key.team())) {
            return; // the bootstrap demo key is unlimited so load tests aren't throttled
        }
        int rpm = key.rpm() > 0 ? key.rpm() : props.rateLimit().defaultRpm();
        long minute = System.currentTimeMillis() / 60000;
        Long count = redis.execute(INCR_WITH_TTL, List.of("rl:" + key.key() + ":" + minute));
        if (count > rpm) {
            metrics.rejection("rate_limit");
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Rate limit of " + rpm + " requests/minute exceeded for this key");
        }
    }
}
