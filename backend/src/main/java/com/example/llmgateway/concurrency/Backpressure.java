package com.example.llmgateway.concurrency;

import com.example.llmgateway.config.GatewayProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;

/**
 * Bounded in-flight work per instance: when the semaphore is exhausted the
 * request is rejected immediately with 503 instead of piling up and
 * collapsing the instance.
 */
@Service
public class Backpressure {

    private final Semaphore semaphore;

    public Backpressure(GatewayProperties props) {
        this.semaphore = new Semaphore(props.getBackpressure().getMaxConcurrent());
    }

    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
    }

    public int available() {
        return semaphore.availablePermits();
    }
}
