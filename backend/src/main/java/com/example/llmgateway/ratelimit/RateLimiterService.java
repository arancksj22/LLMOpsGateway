package com.example.llmgateway.ratelimit;

import com.example.llmgateway.api.GatewayException;
import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.metrics.GatewayMetrics;
import com.example.llmgateway.metrics.StatsService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Distributed per-key rate limiting: a fixed one-minute window counted
 * atomically in Redis, so the limit holds in aggregate across all gateway
 * instances.
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
    private final StatsService stats;

    public RateLimiterService(StringRedisTemplate redis, GatewayProperties props,
                              GatewayMetrics metrics, StatsService stats) {
        this.redis = redis;
        this.props = props;
        this.metrics = metrics;
        this.stats = stats;
    }

    public void check(ApiKeyInfo key) {
        int rpm = key.rpm() > 0 ? key.rpm() : props.getRateLimit().getDefaultRpm();
        if (key.rpm() == 0 && "demo".equals(key.team())) {
            return; // demo key is unlimited so load tests aren't throttled
        }
        long minute = System.currentTimeMillis() / 60000;
        Long count = redis.execute(INCR_WITH_TTL, List.of("rl:" + key.key() + ":" + minute));
        if (count != null && count > rpm) {
            metrics.rejection("rate_limit");
            stats.incr("rejected_rate_limit");
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Rate limit of " + rpm + " requests/minute exceeded for this key");
        }
    }
}
