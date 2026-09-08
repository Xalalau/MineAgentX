package com.mineagent.engine.telemetry;

import com.mineagent.api.llm.LLMResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, local-only counters used to tune the cognition runtime.
 *
 * <p>This deliberately does not send telemetry anywhere and never stores API
 * keys, prompts, or raw world dumps.  It gives the later phases a stable
 * measurement surface for token efficiency, cache behavior, action reliability
 * and memory recall without changing gameplay behavior.</p>
 */
public final class CognitionMetrics {
    private final LongAdder llmCalls = new LongAdder();
    private final LongAdder llmFailures = new LongAdder();
    private final LongAdder llmLatencyNanos = new LongAdder();
    private final LongAdder inputTokens = new LongAdder();
    private final LongAdder cachedInputTokens = new LongAdder();
    private final LongAdder cacheWriteTokens = new LongAdder();
    private final LongAdder outputTokens = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder actions = new LongAdder();
    private final LongAdder successfulActions = new LongAdder();
    private final LongAdder failedActions = new LongAdder();
    private final LongAdder invalidActions = new LongAdder();
    private final LongAdder memoryQueries = new LongAdder();
    private final LongAdder memoryUsefulHits = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> failureKinds = new ConcurrentHashMap<>();

    public void recordLlm(LLMResponse response, long latencyNanos, boolean failed) {
        llmCalls.increment();
        llmLatencyNanos.add(Math.max(0L, latencyNanos));
        if (failed) llmFailures.increment();
        if (response == null || response.usage() == null) return;
        LLMResponse.Usage usage = response.usage();
        inputTokens.add(Math.max(0, usage.promptTokens()));
        cachedInputTokens.add(Math.max(0, usage.cachedPromptTokens()));
        cacheWriteTokens.add(Math.max(0, usage.cacheCreationPromptTokens()));
        outputTokens.add(Math.max(0, usage.completionTokens()));
    }

    public void recordDecisionCache(boolean hit) {
        if (hit) cacheHits.increment(); else cacheMisses.increment();
    }

    public void recordAction(boolean accepted, boolean success, boolean invalid,
                              String failureKind) {
        actions.increment();
        if (accepted && success) successfulActions.increment();
        if (!success) failedActions.increment();
        if (invalid) invalidActions.increment();
        if (failureKind != null && !failureKind.isBlank()) {
            failureKinds.computeIfAbsent(failureKind.trim(), ignored -> new LongAdder())
                    .increment();
        }
    }

    public void recordMemoryQuery(boolean useful) {
        memoryQueries.increment();
        if (useful) memoryUsefulHits.increment();
    }

    public Snapshot snapshot() {
        long calls = llmCalls.sum();
        long totalInput = inputTokens.sum();
        long cached = cachedInputTokens.sum();
        long actionCount = actions.sum();
        long queryCount = memoryQueries.sum();
        return new Snapshot(calls, llmFailures.sum(), llmLatencyNanos.sum(),
                totalInput, cached, cacheWriteTokens.sum(), outputTokens.sum(),
                cacheHits.sum(), cacheMisses.sum(), actionCount,
                successfulActions.sum(), failedActions.sum(), invalidActions.sum(),
                queryCount, memoryUsefulHits.sum(),
                totalInput <= 0 ? 0.0 : (double) cached / totalInput,
                calls <= 0 ? 0.0 : (double) llmLatencyNanos.sum() / calls,
                actionCount <= 0 ? 0.0 : (double) successfulActions.sum() / actionCount,
                queryCount <= 0 ? 0.0 : (double) memoryUsefulHits.sum() / queryCount,
                failureKinds.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().sum())));
    }

    public record Snapshot(long llmCalls, long llmFailures, long llmLatencyNanos,
                           long inputTokens, long cachedInputTokens,
                           long cacheWriteTokens, long outputTokens,
                           long decisionCacheHits, long decisionCacheMisses,
                           long actions, long successfulActions, long failedActions,
                           long invalidActions, long memoryQueries,
                           long usefulMemoryHits, double providerCacheHitRate,
                           double averageLlmLatencyNanos,
                           double actionSuccessRate, double memoryHitRate,
                           Map<String, Long> failureKinds) {
        public long averageLlmLatencyMillis() {
            return Math.round(averageLlmLatencyNanos / 1_000_000.0);
        }
    }
}
