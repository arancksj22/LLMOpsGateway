package com.example.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String adminKey = "admin-secret";
    private String demoKey = "gw_demo";
    private String instanceId = "local";
    private ExactCache exactCache = new ExactCache();
    private SemanticCache semanticCache = new SemanticCache();
    private Compression compression = new Compression();
    private RateLimit rateLimit = new RateLimit();
    private Budget budget = new Budget();
    private Backpressure backpressure = new Backpressure();
    private CircuitBreakerProps circuitBreaker = new CircuitBreakerProps();
    private Embedding embedding = new Embedding();
    private Qdrant qdrant = new Qdrant();
    private Providers providers = new Providers();
    private Map<String, ModelPricing> pricing = new HashMap<>();

    public static class ExactCache {
        private boolean enabled = true;
        private long ttlSeconds = 3600;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    public static class SemanticCache {
        private boolean enabled = true;
        private double threshold = 0.87;
        private long ttlSeconds = 3600;
        private double maxTemperature = 0.7;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public double getMaxTemperature() { return maxTemperature; }
        public void setMaxTemperature(double maxTemperature) { this.maxTemperature = maxTemperature; }
    }

    public static class Compression {
        private boolean enabled = true;
        private int minWords = 40;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinWords() { return minWords; }
        public void setMinWords(int minWords) { this.minWords = minWords; }
    }

    public static class RateLimit {
        private int defaultRpm = 120;
        public int getDefaultRpm() { return defaultRpm; }
        public void setDefaultRpm(int defaultRpm) { this.defaultRpm = defaultRpm; }
    }

    public static class Budget {
        private double orgMonthlyUsd = 25.0;
        private double defaultKeyMonthlyUsd = 10.0;
        private boolean enforce = true;
        public double getOrgMonthlyUsd() { return orgMonthlyUsd; }
        public void setOrgMonthlyUsd(double orgMonthlyUsd) { this.orgMonthlyUsd = orgMonthlyUsd; }
        public double getDefaultKeyMonthlyUsd() { return defaultKeyMonthlyUsd; }
        public void setDefaultKeyMonthlyUsd(double defaultKeyMonthlyUsd) { this.defaultKeyMonthlyUsd = defaultKeyMonthlyUsd; }
        public boolean isEnforce() { return enforce; }
        public void setEnforce(boolean enforce) { this.enforce = enforce; }
    }

    public static class Backpressure {
        private int maxConcurrent = 64;
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    }

    public static class CircuitBreakerProps {
        private int failureThreshold = 3;
        private int cooldownSeconds = 30;
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    }

    public static class Embedding {
        /** "hash" (local, zero-dep) or "gemini" (free hosted API, real semantic embeddings). */
        private String provider = "hash";
        private int dimension = 384;
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
    }

    public static class Qdrant {
        private String url = "http://localhost:6333";
        private String collection = "semantic_cache";
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
    }

    public static class Providers {
        private List<String> order = new ArrayList<>(List.of("groq", "gemini", "mock"));
        private Groq groq = new Groq();
        private Gemini gemini = new Gemini();
        private Mock mock = new Mock();
        public List<String> getOrder() { return order; }
        public void setOrder(List<String> order) { this.order = order; }
        public Groq getGroq() { return groq; }
        public void setGroq(Groq groq) { this.groq = groq; }
        public Gemini getGemini() { return gemini; }
        public void setGemini(Gemini gemini) { this.gemini = gemini; }
        public Mock getMock() { return mock; }
        public void setMock(Mock mock) { this.mock = mock; }
    }

    public static class Groq {
        private String apiKey = "";
        private String baseUrl = "https://api.groq.com/openai/v1";
        private String model = "llama-3.1-8b-instant";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class Gemini {
        private String apiKey = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String model = "gemini-2.0-flash";
        private String embeddingModel = "text-embedding-004";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    }

    public static class Mock {
        private long latencyMs = 200;
        private String model = "mock-model";
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class ModelPricing {
        /** USD per 1M input tokens. */
        private double input = 0.1;
        /** USD per 1M output tokens. */
        private double output = 0.4;
        public double getInput() { return input; }
        public void setInput(double input) { this.input = input; }
        public double getOutput() { return output; }
        public void setOutput(double output) { this.output = output; }
    }

    public String getAdminKey() { return adminKey; }
    public void setAdminKey(String adminKey) { this.adminKey = adminKey; }
    public String getDemoKey() { return demoKey; }
    public void setDemoKey(String demoKey) { this.demoKey = demoKey; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public ExactCache getExactCache() { return exactCache; }
    public void setExactCache(ExactCache exactCache) { this.exactCache = exactCache; }
    public SemanticCache getSemanticCache() { return semanticCache; }
    public void setSemanticCache(SemanticCache semanticCache) { this.semanticCache = semanticCache; }
    public Compression getCompression() { return compression; }
    public void setCompression(Compression compression) { this.compression = compression; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }
    public Backpressure getBackpressure() { return backpressure; }
    public void setBackpressure(Backpressure backpressure) { this.backpressure = backpressure; }
    public CircuitBreakerProps getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerProps circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Qdrant getQdrant() { return qdrant; }
    public void setQdrant(Qdrant qdrant) { this.qdrant = qdrant; }
    public Providers getProviders() { return providers; }
    public void setProviders(Providers providers) { this.providers = providers; }
    public Map<String, ModelPricing> getPricing() { return pricing; }
    public void setPricing(Map<String, ModelPricing> pricing) { this.pricing = pricing; }
}
