package com.example.llmgateway.concurrency;

import com.example.llmgateway.cache.CachedResponse;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Single-flight coalescing — when many identical requests hit an empty cache
 * at the same time (possibly on different instances), only ONE provider call
 * happens. The first caller to grab the Redis lock for the request hash
 * becomes the leader and does the work; everyone else polls Redis for the
 * leader's result instead of calling the provider themselves. If the leader
 * dies or fails, the lock expires / is released and a waiter takes over.
 */
@Service
public class SingleFlight {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration RESULT_TTL = Duration.ofSeconds(60);

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
        // follower: poll for the leader's result (up to 15s), take over if the leader vanishes
        for (int i = 0; i < 150; i++) {
            sleep(100);
            String json = redis.opsForValue().get(resultKey);
            if (json != null) {
                return new Outcome(mapper.readValue(json, CachedResponse.class), true);
            }
            if (!Boolean.TRUE.equals(redis.hasKey(lockKey)) && tryLock(lockKey)) {
                String late = redis.opsForValue().get(resultKey);
                if (late != null) {
                    redis.delete(lockKey);
                    return new Outcome(mapper.readValue(late, CachedResponse.class), true);
                }
                return lead(lockKey, resultKey, leaderWork);
            }
        }
        return new Outcome(leaderWork.get(), false); // waited too long — just do the call
    }

    private Outcome lead(String lockKey, String resultKey, Supplier<CachedResponse> leaderWork) {
        try {
            CachedResponse result = leaderWork.get();
            redis.opsForValue().set(resultKey, mapper.writeValueAsString(result), RESULT_TTL);
            return new Outcome(result, false);
        } finally {
            redis.delete(lockKey);
        }
    }

    private boolean tryLock(String lockKey) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
