package com.example.llmgateway.concurrency;

import com.example.llmgateway.cache.CachedResponse;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cross-instance request coalescing: the first instance to grab the Redis
 * lock for a request hash becomes the leader and calls the provider; every
 * other concurrent duplicate (on any instance) polls for the leader's result
 * instead of making its own provider call.
 */
@Service
public class SingleFlight {

    private static final Logger log = LoggerFactory.getLogger(SingleFlight.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration RESULT_TTL = Duration.ofSeconds(60);
    private static final int MAX_POLLS = 150;
    private static final long POLL_MS = 100;

    public record Outcome(CachedResponse response, boolean coalesced) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public SingleFlight(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Outcome run(String hash, Supplier<CachedResponse> leaderWork) {
        String lockKey = "sf:lock:" + hash;
        String resultKey = "sf:result:" + hash;

        if (tryLock(lockKey)) {
            return lead(lockKey, resultKey, leaderWork);
        }
        for (int i = 0; i < MAX_POLLS; i++) {
            sleep(POLL_MS);
            String json = redis.opsForValue().get(resultKey);
            if (json != null) {
                CachedResponse r = fromJson(json);
                if (r != null) {
                    return new Outcome(r, true);
                }
            }
            // leader may have died or failed — try to take over
            if (!Boolean.TRUE.equals(redis.hasKey(lockKey)) && tryLock(lockKey)) {
                String late = redis.opsForValue().get(resultKey);
                if (late != null) {
                    redis.delete(lockKey);
                    CachedResponse r = fromJson(late);
                    if (r != null) {
                        return new Outcome(r, true);
                    }
                }
                return lead(lockKey, resultKey, leaderWork);
            }
        }
        log.warn("Single-flight wait timed out for {}, falling through to direct call", hash);
        return new Outcome(leaderWork.get(), false);
    }

    private Outcome lead(String lockKey, String resultKey, Supplier<CachedResponse> leaderWork) {
        try {
            CachedResponse result = leaderWork.get();
            try {
                redis.opsForValue().set(resultKey, mapper.writeValueAsString(result), RESULT_TTL);
            } catch (Exception ignored) {
            }
            return new Outcome(result, false);
        } finally {
            redis.delete(lockKey);
        }
    }

    private boolean tryLock(String lockKey) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
    }

    private CachedResponse fromJson(String json) {
        try {
            return mapper.readValue(json, CachedResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
