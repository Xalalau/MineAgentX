package com.mineagent.api.llm;

import java.util.EnumSet;
import java.util.Set;

/**
 * Wire capabilities advertised by an LLM provider.
 *
 * <p>Capabilities are deliberately data rather than model-name checks.  A
 * relay may expose a newer model through an older protocol, and a local
 * server may expose no authentication at all.  The agent therefore negotiates
 * the features it can use before sending provider-specific fields.</p>
 */
public record LLMCapabilities(Set<Feature> features,
                              int maxContextTokens,
                              int maxOutputTokens) {
    public enum Feature {
        CHAT_COMPLETIONS,
        /** AWS Bedrock Runtime Converse/ConverseStream wire protocol. */
        CONVERSE,
        RESPONSES,
        STATEFUL_RESPONSES,
        SERVER_COMPACTION,
        OPAQUE_REASONING_STATE,
        PROMPT_CACHE_KEY,
        PROMPT_CACHE_BREAKPOINT,
        STRUCTURED_OUTPUTS,
        FUNCTION_CALLING,
        TOOL_SEARCH,
        STREAMING
    }

    public LLMCapabilities {
        features = features == null || features.isEmpty()
                ? Set.of() : Set.copyOf(features);
        maxContextTokens = Math.max(0, maxContextTokens);
        maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    public boolean supports(Feature feature) {
        return feature != null && features.contains(feature);
    }

    public static LLMCapabilities chatDefaults() {
        return new LLMCapabilities(EnumSet.of(
                Feature.CHAT_COMPLETIONS, Feature.FUNCTION_CALLING), 0, 0);
    }

    public static LLMCapabilities responsesDefaults() {
        return new LLMCapabilities(EnumSet.of(
                Feature.RESPONSES, Feature.STATEFUL_RESPONSES,
                Feature.OPAQUE_REASONING_STATE, Feature.STRUCTURED_OUTPUTS,
                Feature.FUNCTION_CALLING, Feature.STREAMING), 0, 0);
    }
}
