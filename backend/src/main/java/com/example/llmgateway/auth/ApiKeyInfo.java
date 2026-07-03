package com.example.llmgateway.auth;

/**
 * A scoped internal key an application uses to call the gateway.
 * rpm <= 0 means unlimited; monthlyBudgetUsd <= 0 means use the configured default.
 */
public record ApiKeyInfo(
        String key,
        String name,
        String team,
        int rpm,
        double monthlyBudgetUsd,
        boolean revoked,
        long createdAt
) {
}
