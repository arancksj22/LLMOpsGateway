package com.example.llmgateway;

import com.example.llmgateway.embedding.EmbeddingService;
import com.example.llmgateway.embedding.HashingEmbeddingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests only — cluster behaviors are validated by scripts/distributed_check.py. */
class LlmgatewayApplicationTests {

	@Test
	void rewordedQueriesAreMoreSimilarThanUnrelatedOnes() {
		HashingEmbeddingService e = new HashingEmbeddingService(384);
		double reworded = EmbeddingService.cosine(
				e.embed("What is the capital of France?"),
				e.embed("Tell me the capital city of France"));
		double unrelated = EmbeddingService.cosine(
				e.embed("What is the capital of France?"),
				e.embed("How do I bake sourdough bread at home?"));
		assertTrue(reworded > unrelated, "reworded=" + reworded + " unrelated=" + unrelated);
		assertTrue(reworded > 0.5, "reworded similarity too low: " + reworded);
	}
}
