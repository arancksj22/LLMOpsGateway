package com.example.llmgateway.config;

import com.example.llmgateway.compress.PromptCompressor;
import com.example.llmgateway.embedding.EmbeddingService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    /** Virtual threads for SSE streaming work. */
    @Bean
    public ExecutorService sseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public EmbeddingService embeddingService(GatewayProperties props, RestClient rest) {
        return new EmbeddingService(props.embedding(), props.providers().gemini(), rest);
    }

    @Bean
    public PromptCompressor promptCompressor(GatewayProperties props) {
        return new PromptCompressor(props.compression());
    }

    /** Publish histogram buckets + p50/p95/p99 for gateway latency timers. */
    @Bean
    public MeterFilter gatewayPercentiles() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getName().startsWith("gateway.request")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .percentiles(0.5, 0.95, 0.99)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }
}
