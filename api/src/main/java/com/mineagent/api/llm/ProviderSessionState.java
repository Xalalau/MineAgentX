package com.mineagent.api.llm;

/**
 * Durable provider state, kept separate from gameplay memory.  In particular,
 * encrypted reasoning/compaction payloads are transport state and must not be
 * searched, summarized, or shown to a player.
 */
public record ProviderSessionState(String providerId, String model,
                                   String conversationId,
                                   String previousResponseId,
                                   String opaqueCompactionItem,
                                   long revision) {
    /**
     * Provider-owned state is persisted with a hard bound.  Responses
     * reasoning/compaction payloads are opaque and can be encrypted, so an
     * upstream or relay bug must not turn one companion file into an
     * unbounded memory/disk sink.  The bound is deliberately shared by the
     * API contract and the provider implementation rather than being enforced
     * only at one call site.
     */
    private static final int MAX_CONVERSATION_ID = 256;
    private static final int MAX_RESPONSE_ID = 512;
    private static final int MAX_OPAQUE_STATE = 262_144;

    public ProviderSessionState {
        providerId = normalize(providerId);
        model = normalize(model);
        conversationId = bounded(blankToNull(conversationId), MAX_CONVERSATION_ID);
        previousResponseId = bounded(blankToNull(previousResponseId), MAX_RESPONSE_ID);
        opaqueCompactionItem = bounded(blankToNull(opaqueCompactionItem), MAX_OPAQUE_STATE);
        revision = Math.max(0L, revision);
    }

    public static ProviderSessionState empty(String providerId, String model) {
        return new ProviderSessionState(providerId, model, null, null, null, 0L);
    }

    public ProviderSessionState withResponse(String responseId) {
        return new ProviderSessionState(providerId, model, conversationId,
                responseId, opaqueCompactionItem, revision + 1L);
    }

    /**
     * Bind a restored/legacy state to the stable companion conversation.
     * Older v0.3.x files did not persist this identity; without it a singleton
     * provider cannot isolate two companions that use the same model.
     */
    public ProviderSessionState withConversationId(String id) {
        return new ProviderSessionState(providerId, model, id,
                previousResponseId, opaqueCompactionItem, revision + 1L);
    }

    public ProviderSessionState withCompaction(String item) {
        return new ProviderSessionState(providerId, model, conversationId,
                previousResponseId, item, revision + 1L);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }
}
