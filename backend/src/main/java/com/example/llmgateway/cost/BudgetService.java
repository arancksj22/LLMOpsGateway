package com.example.llmgateway.cost;

import com.example.llmgateway.api.GatewayException;
import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.metrics.GatewayMetrics;
import com.example.llmgateway.metrics.StatsService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Org / team / key monthly spend tracked with atomic Redis counters —
 * consistent under concurrent updates from all instances.
 */
@Service
public class BudgetService {

    private final StringRedisTemplate redis;
    private final GatewayProperties props;
    private final GatewayMetrics metrics;
    private final StatsService stats;

    public BudgetService(StringRedisTemplate redis, GatewayProperties props,
                         GatewayMetrics metrics, StatsService stats) {
        this.redis = redis;
        this.props = props;
        this.metrics = metrics;
        this.stats = stats;
    }

    private String month() {
        return YearMonth.now().toString();
    }

    public void check(ApiKeyInfo key) {
        if (!props.getBudget().isEnforce()) {
            return;
        }
        double orgCap = props.getBudget().getOrgMonthlyUsd();
        if (orgCap > 0 && orgSpend() >= orgCap) {
            reject("Org monthly budget of $" + orgCap + " exhausted");
        }
        double keyCap = key.monthlyBudgetUsd() > 0 ? key.monthlyBudgetUsd() : props.getBudget().getDefaultKeyMonthlyUsd();
        if (keyCap > 0 && spend("spend:key:" + key.key()) >= keyCap) {
            reject("Monthly budget of $" + keyCap + " exhausted for this key");
        }
    }

    private void reject(String message) {
        metrics.rejection("budget");
        stats.incr("rejected_budget");
        throw new GatewayException(HttpStatus.PAYMENT_REQUIRED, "budget_exceeded", message);
    }

    public void record(ApiKeyInfo key, double costUsd) {
        try {
            String m = ":" + month();
            redis.opsForValue().increment("spend:org" + m, costUsd);
            redis.opsForValue().increment("spend:team:" + key.team() + m, costUsd);
            redis.opsForValue().increment("spend:key:" + key.key() + m, costUsd);
        } catch (Exception ignored) {
        }
    }

    public double orgSpend() {
        return spend("spend:org");
    }

    public Map<String, Double> spendByTeam() {
        Map<String, Double> out = new LinkedHashMap<>();
        Set<String> keys = redis.keys("spend:team:*:" + month());
        if (keys != null) {
            for (String k : keys) {
                String team = k.substring("spend:team:".length(), k.length() - month().length() - 1);
                out.put(team, spend("spend:team:" + team));
            }
        }
        return out;
    }

    private double spend(String prefix) {
        String v = redis.opsForValue().get(prefix + ":" + month());
        return v == null ? 0 : Double.parseDouble(v);
    }
}
