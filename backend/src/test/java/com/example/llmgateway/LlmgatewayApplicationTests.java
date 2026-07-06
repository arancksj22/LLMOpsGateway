package com.example.llmgateway;

import com.example.llmgateway.config.GatewayProperties;
import com.example.llmgateway.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests only — cluster behaviors are validated by scripts/distributed_check.py. */
class LlmgatewayApplicationTests {

	@Test
	void rewordedQueriesAreMoreSimilarThanUnrelatedOnes() {
		EmbeddingService e = new EmbeddingService(
				new GatewayProperties.Embedding("hash", 384), null, null);
		double reworded = e.similarity(
				"What is the capital of France?",
				"Tell me the capital city of France");
		double unrelated = e.similarity(
				"What is the capital of France?",
				"How do I bake sourdough bread at home?");
		assertTrue(reworded > unrelated, "reworded=" + reworded + " unrelated=" + unrelated);
		assertTrue(reworded > 0.5, "reworded similarity too low: " + reworded);
	}
}
