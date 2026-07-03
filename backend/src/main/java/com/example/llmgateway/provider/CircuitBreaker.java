package com.example.llmgateway.provider;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal per-instance circuit breaker: opens after N consecutive failures,
 * allows a single probe after the cooldown.
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final long cooldownMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public boolean allow() {
        long until = openUntil.get();
        if (until == 0) {
            return true;
        }
        if (System.currentTimeMillis() >= until) {
            // half-open: let one probe through, others stay blocked until it succeeds
            return openUntil.compareAndSet(until, System.currentTimeMillis() + cooldownMs);
        }
        return false;
    }

    public void success() {
        consecutiveFailures.set(0);
        openUntil.set(0);
    }

    public void failure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil.set(System.currentTimeMillis() + cooldownMs);
        }
    }

    public boolean isOpen() {
        return openUntil.get() > System.currentTimeMillis();
    }
}
