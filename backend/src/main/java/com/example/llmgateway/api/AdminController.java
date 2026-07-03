package com.example.llmgateway.api;

import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.auth.ApiKeyService;
import com.example.llmgateway.cache.SemanticCacheService;
import com.example.llmgateway.concurrency.Backpressure;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.cost.BudgetService;
import com.example.llmgateway.metrics.StatsService;
import com.example.llmgateway.provider.ProviderRouter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Control plane: internal key management, live stats, similarity debug. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ApiKeyService keys;
    private final StatsService stats;
    private final BudgetService budget;
    private final SemanticCacheService semanticCache;
    private final ProviderRouter router;
    private final Backpressure backpressure;
    private final GatewayProperties props;

    public AdminController(ApiKeyService keys, StatsService stats, BudgetService budget,
                           SemanticCacheService semanticCache, ProviderRouter router,
                           Backpressure backpressure, GatewayProperties props) {
        this.keys = keys;
        this.stats = stats;
        this.budget = budget;
        this.semanticCache = semanticCache;
        this.router = router;
        this.backpressure = backpressure;
        this.props = props;
    }

    private void requireAdmin(String adminKey) {
        if (adminKey == null || !adminKey.equals(props.getAdminKey())) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid admin key");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateKeyRequest(String name, String team, Integer rpm, Double monthlyBudgetUsd) {
    }

    @PostMapping("/keys")
    public ApiKeyInfo createKey(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                @RequestBody CreateKeyRequest req) {
        requireAdmin(adminKey);
        return keys.create(
                req.name() == null ? "unnamed" : req.name(),
                req.team() == null ? "default" : req.team(),
                req.rpm() == null ? 0 : req.rpm(),
                req.monthlyBudgetUsd() == null ? 0 : req.monthlyBudgetUsd());
    }

    @GetMapping("/keys")
    public List<ApiKeyInfo> listKeys(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        requireAdmin(adminKey);
        return keys.list();
    }

    @DeleteMapping("/keys/{key}")
    public Map<String, Object> revokeKey(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                         @PathVariable String key) {
        requireAdmin(adminKey);
        return Map.of("revoked", keys.revoke(key));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>(stats.snapshot());
        out.put("org_spend_usd", budget.orgSpend());
        out.put("spend_by_team", budget.spendByTeam());
        out.put("providers", router.status());
        out.put("instance", props.getInstanceId());
        out.put("backpressure_available", backpressure.available());
        out.put("semantic_threshold", props.getSemanticCache().getThreshold());
        return out;
    }

    /** Debug helper used by the threshold-evaluation script. */
    @GetMapping("/similarity")
    public Map<String, Object> similarity(@RequestParam String a, @RequestParam String b) {
        return Map.of("similarity", semanticCache.similarity(a, b));
    }
}
