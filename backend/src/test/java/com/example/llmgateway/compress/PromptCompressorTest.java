package com.example.llmgateway.compress;

import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCompressorTest {

	private final PromptCompressor compressor =
			new PromptCompressor(new GatewayProperties.Compression(true, 40));

	@Test
	void longPromptsGetMeasurablySmaller() {
		String longPrompt = ("I would like you to basically explain, in a really very detailed way, " +
				"the concept of distributed caching. Please note that I want examples. " +
				"I would like you to basically explain, in a really very detailed way, " +
				"the concept of distributed caching. Please note that I want examples. " +
				"Could you please also cover cache invalidation and the trade-offs involved?");
		PromptCompressor.Result r = compressor.compress(List.of(new Message("user", longPrompt)));
		assertTrue(r.applied());
		assertTrue(r.tokensAfter() < r.tokensBefore(),
				"expected fewer tokens, got " + r.tokensBefore() + " -> " + r.tokensAfter());
	}

	@Test
	void shortPromptsAreLeftAlone() {
		PromptCompressor.Result r = compressor.compress(List.of(new Message("user", "What is 2+2?")));
		assertEquals("What is 2+2?", r.messages().get(0).content());
	}
}
