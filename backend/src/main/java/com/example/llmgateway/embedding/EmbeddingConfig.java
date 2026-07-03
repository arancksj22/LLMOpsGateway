package com.example.llmgateway.embedding;

import com.example.llmgateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingService embeddingService(GatewayProperties props, RestClient rest) {
        if ("gemini".equalsIgnoreCase(props.getEmbedding().getProvider())) {
            return new GeminiEmbeddingService(rest, props);
        }
        return new HashingEmbeddingService(props.getEmbedding().getDimension());
    }
}
