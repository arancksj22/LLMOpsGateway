package com.example.llmgateway.metrics;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cluster-wide live counters in a single Redis hash, so the demo UI shows
 * consistent numbers no matter which instance serves it.
 */
@Service
public class StatsService {

    private static final String KEY = "gw:stats";

    private final StringRedisTemplate redis;

    public StatsService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void incr(String field) {
        try {
            redis.opsForHash().increment(KEY, field, 1);
        } catch (Exception ignored) {
        }
    }

    public void incrBy(String field, long amount) {
        try {
            redis.opsForHash().increment(KEY, field, amount);
        } catch (Exception ignored) {
        }
    }

    public void incrByFloat(String field, double amount) {
        try {
            redis.opsForHash().increment(KEY, field, amount);
        } catch (Exception ignored) {
        }
    }

    public Map<String, Object> snapshot() {
        Map<Object, Object> raw = redis.opsForHash().entries(KEY);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            String v = (String) e.getValue();
            try {
                out.put((String) e.getKey(), v.contains(".") ? Double.parseDouble(v) : Long.parseLong(v));
            } catch (NumberFormatException ex) {
                out.put((String) e.getKey(), v);
            }
        }
        long requests = asLong(out.get("requests"));
        long exact = asLong(out.get("exact_hits"));
        long semantic = asLong(out.get("semantic_hits"));
        out.put("hit_rate", requests == 0 ? 0.0 : Math.round(1000.0 * (exact + semantic) / requests) / 1000.0);
        return out;
    }

    private long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
