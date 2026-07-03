package com.example.llmgateway.auth;

import com.example.llmgateway.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final String KEY_PREFIX = "apikey:";
    private static final String KEY_SET = "apikeys";

    private final StringRedisTemplate redis;
    private final GatewayProperties props;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(StringRedisTemplate redis, GatewayProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Bootstrap a demo key so the UI and load tests work out of the box. */
    @PostConstruct
    public void bootstrapDemoKey() {
        try {
            String demo = props.getDemoKey();
            if (demo != null && !demo.isBlank() && get(demo) == null) {
                save(new ApiKeyInfo(demo, "demo", "demo", 0, 0, false, System.currentTimeMillis()));
                log.info("Created demo API key '{}'", demo);
            }
        } catch (Exception e) {
            log.warn("Could not bootstrap demo key (Redis unavailable?): {}", e.getMessage());
        }
    }

    public ApiKeyInfo create(String name, String team, int rpm, double monthlyBudgetUsd) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String key = "gw_" + HexFormat.of().formatHex(bytes);
        ApiKeyInfo info = new ApiKeyInfo(key, name, team, rpm, monthlyBudgetUsd, false, System.currentTimeMillis());
        save(info);
        return info;
    }

    public ApiKeyInfo get(String key) {
        Map<Object, Object> h = redis.opsForHash().entries(KEY_PREFIX + key);
        if (h == null || h.isEmpty()) {
            return null;
        }
        return new ApiKeyInfo(
                key,
                (String) h.getOrDefault("name", ""),
                (String) h.getOrDefault("team", "default"),
                Integer.parseInt((String) h.getOrDefault("rpm", "0")),
                Double.parseDouble((String) h.getOrDefault("monthlyBudgetUsd", "0")),
                Boolean.parseBoolean((String) h.getOrDefault("revoked", "false")),
                Long.parseLong((String) h.getOrDefault("createdAt", "0"))
        );
    }

    public List<ApiKeyInfo> list() {
        Set<String> keys = redis.opsForSet().members(KEY_SET);
        List<ApiKeyInfo> out = new ArrayList<>();
        if (keys != null) {
            for (String k : keys) {
                ApiKeyInfo info = get(k);
                if (info != null) {
                    out.add(info);
                }
            }
        }
        return out;
    }

    public boolean revoke(String key) {
        if (get(key) == null) {
            return false;
        }
        redis.opsForHash().put(KEY_PREFIX + key, "revoked", "true");
        return true;
    }

    private void save(ApiKeyInfo info) {
        redis.opsForHash().putAll(KEY_PREFIX + info.key(), Map.of(
                "name", info.name(),
                "team", info.team(),
                "rpm", String.valueOf(info.rpm()),
                "monthlyBudgetUsd", String.valueOf(info.monthlyBudgetUsd()),
                "revoked", String.valueOf(info.revoked()),
                "createdAt", String.valueOf(info.createdAt())
        ));
        redis.opsForSet().add(KEY_SET, info.key());
    }
}
