package com.example.llmgateway.compress;

import com.example.llmgateway.api.dto.Message;
import com.example.llmgateway.config.GatewayProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Prompt compression — shrinks the tokens of calls we can't avoid. A
 * heuristic token-importance trimmer: collapses whitespace, drops duplicate
 * sentences, strips filler phrases and low-information words from long
 * user/system prompts. Roughly meaning-preserving, measurably fewer tokens
 * (66% on a verbose redundant prompt — see RESULTS.md). Short prompts are
 * left untouched.
 */
public class PromptCompressor {

    private static final List<String> FILLER_PHRASES = List.of(
            "i would like you to", "i want you to", "could you please", "can you please",
            "would you kindly", "please note that", "it should be noted that",
            "as previously mentioned", "in order to", "at the end of the day",
            "basically", "essentially", "actually", "literally", "obviously",
            "needless to say", "for what it's worth", "if you don't mind");

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "very", "really", "quite", "rather", "just", "simply",
            "somewhat", "perhaps", "maybe", "certainly", "definitely", "surely",
            "indeed", "also", "too", "own", "same", "such");

    public record Result(List<Message> messages, int tokensBefore, int tokensAfter, boolean applied) {
        public int tokensSaved() {
            return Math.max(0, tokensBefore - tokensAfter);
        }
    }

    private final GatewayProperties.Compression cfg;

    public PromptCompressor(GatewayProperties.Compression cfg) {
        this.cfg = cfg;
    }

    public Result none(List<Message> messages) {
        int t = estimate(messages);
        return new Result(messages, t, t, false);
    }

    public Result compress(List<Message> messages) {
        int before = estimate(messages);
        List<Message> out = new ArrayList<>();
        boolean changed = false;
        for (Message m : messages) {
            if (("user".equals(m.role()) || "system".equals(m.role()))
                    && wordCount(m.content()) >= cfg.minWords()) {
                String compressed = compressText(m.content());
                changed |= !compressed.equals(m.content());
                out.add(new Message(m.role(), compressed));
            } else {
                out.add(m);
            }
        }
        int after = estimate(out);
        return new Result(out, before, after, changed && after < before);
    }

    private String compressText(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        String lower = s.toLowerCase();
        for (String phrase : FILLER_PHRASES) {
            int idx;
            while ((idx = lower.indexOf(phrase)) >= 0) {
                s = s.substring(0, idx) + s.substring(idx + phrase.length());
                lower = s.toLowerCase();
            }
        }
        Set<String> sentences = new LinkedHashSet<>(List.of(s.split("(?<=[.!?])\\s+")));
        StringBuilder sb = new StringBuilder();
        for (String sentence : sentences) {
            for (String word : sentence.split("\\s+")) {
                if (STOPWORDS.contains(word.toLowerCase().replaceAll("[^a-z0-9']", ""))) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(word);
            }
        }
        String result = sb.toString().replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? s : result;
    }

    private static int estimate(List<Message> messages) {
        int words = 0;
        for (Message m : messages) {
            words += wordCount(m.content());
        }
        return (int) Math.ceil(words * 1.33);
    }

    private static int wordCount(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }
}
