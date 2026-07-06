package com.example.llmgateway.auth;

import com.example.llmgateway.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Internal API-key management — the centralized-key story: real provider
 * keys live server-side only; each application gets its own scoped,
 * revocable gateway key (with optional rpm limit and monthly budget) stored
 * in Redis so every instance sees the same key set instantly.
 */
@Service
public class ApiKeyService {

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
        if (get(props.demoKey()) == null) {
            save(new ApiKeyInfo(props.demoKey(), "demo", "demo", 0, 0, false, System.currentTimeMillis()));
        }
    }

    public ApiKeyInfo create(String name, String team, int rpm, double monthlyBudgetUsd) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        ApiKeyInfo info = new ApiKeyInfo("gw_" + HexFormat.of().formatHex(bytes),
                name, team, rpm, monthlyBudgetUsd, false, System.currentTimeMillis());
        save(info);
        return info;
    }

    public ApiKeyInfo get(String key) {
        Map<Object, Object> h = redis.opsForHash().entries(KEY_PREFIX + key);
        if (h.isEmpty()) {
            return null;
        }
        return new ApiKeyInfo(
                key,
                (String) h.get("name"),
                (String) h.get("team"),
                Integer.parseInt((String) h.get("rpm")),
                Double.parseDouble((String) h.get("monthlyBudgetUsd")),
                Boolean.parseBoolean((String) h.get("revoked")),
                Long.parseLong((String) h.get("createdAt")));
    }

    public List<ApiKeyInfo> list() {
        List<ApiKeyInfo> out = new ArrayList<>();
        for (String k : redis.opsForSet().members(KEY_SET)) {
            ApiKeyInfo info = get(k);
            if (info != null) {
                out.add(info);
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
                "createdAt", String.valueOf(info.createdAt())));
        redis.opsForSet().add(KEY_SET, info.key());
    }
}
