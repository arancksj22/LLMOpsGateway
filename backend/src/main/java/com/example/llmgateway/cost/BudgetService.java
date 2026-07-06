package com.example.llmgateway.cost;

import com.example.llmgateway.api.GatewayException;
import com.example.llmgateway.auth.ApiKeyInfo;
import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.metrics.GatewayMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cost accounting + spend caps. Per-call cost = tokens x per-model pricing
 * from config; monthly spend is tracked per org / team / key with atomic
 * Redis INCRBYFLOAT counters, so totals stay consistent while every gateway
 * instance records concurrently — and caps are enforced against the true
 * org-wide total, which no single app could see on its own.
 */
@Service
public class BudgetService {

    private final StringRedisTemplate redis;
    private final GatewayProperties props;
    private final GatewayMetrics metrics;

    public BudgetService(StringRedisTemplate redis, GatewayProperties props, GatewayMetrics metrics) {
        this.redis = redis;
        this.props = props;
        this.metrics = metrics;
    }

    public double cost(String model, int promptTokens, int completionTokens) {
        GatewayProperties.Pricing p = props.pricing().getOrDefault(model, new GatewayProperties.Pricing(0.1, 0.4));
        return promptTokens * p.input() / 1_000_000.0 + completionTokens * p.output() / 1_000_000.0;
    }

    /** Called before any provider call; throws 402 when a cap is exhausted. */
    public void check(ApiKeyInfo key) {
        if (!props.budget().enforce()) {
            return;
        }
        double orgCap = props.budget().orgMonthlyUsd();
        if (orgCap > 0 && orgSpend() >= orgCap) {
            reject("Org monthly budget of $" + orgCap + " exhausted");
        }
        double keyCap = key.monthlyBudgetUsd() > 0 ? key.monthlyBudgetUsd() : props.budget().defaultKeyMonthlyUsd();
        if (keyCap > 0 && spend("spend:key:" + key.key()) >= keyCap) {
            reject("Monthly budget of $" + keyCap + " exhausted for this key");
        }
    }

    public void record(ApiKeyInfo key, double costUsd) {
        String m = ":" + month();
        redis.opsForValue().increment("spend:org" + m, costUsd);
        redis.opsForValue().increment("spend:team:" + key.team() + m, costUsd);
        redis.opsForValue().increment("spend:key:" + key.key() + m, costUsd);
    }

    public double orgSpend() {
        return spend("spend:org");
    }

    public Map<String, Double> spendByTeam() {
        Map<String, Double> out = new LinkedHashMap<>();
        Set<String> keys = redis.keys("spend:team:*:" + month());
        for (String k : keys) {
            String team = k.substring("spend:team:".length(), k.length() - month().length() - 1);
            out.put(team, spend("spend:team:" + team));
        }
        return out;
    }

    private void reject(String message) {
        metrics.rejection("budget");
        throw new GatewayException(HttpStatus.PAYMENT_REQUIRED, "budget_exceeded", message);
    }

    private String month() {
        return YearMonth.now().toString();
    }

    private double spend(String prefix) {
        String v = redis.opsForValue().get(prefix + ":" + month());
        return v == null ? 0 : Double.parseDouble(v);
    }
}
