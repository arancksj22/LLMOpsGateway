package com.example.llmgateway.cost;

import com.example.llmgateway.config.GatewayProperties;
import org.springframework.stereotype.Service;

/** Per-call cost from tokens × configured per-model pricing (USD per 1M tokens). */
@Service
public class CostService {

    private final GatewayProperties props;

    public CostService(GatewayProperties props) {
        this.props = props;
    }

    public double cost(String model, int promptTokens, int completionTokens) {
        GatewayProperties.ModelPricing p = props.getPricing().getOrDefault(model, new GatewayProperties.ModelPricing());
        return promptTokens * p.getInput() / 1_000_000.0 + completionTokens * p.getOutput() / 1_000_000.0;
    }
}
