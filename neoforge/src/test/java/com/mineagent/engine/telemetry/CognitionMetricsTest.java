package com.mineagent.engine.telemetry;

import com.mineagent.api.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CognitionMetricsTest {
    @Test
    void recordsProviderCacheAndActionRates() {
        CognitionMetrics metrics = new CognitionMetrics();
        metrics.recordLlm(new LLMResponse("id", "model", null,
                new LLMResponse.Usage(100, 20, 120, 75, 0), "stop"), 1_000_000L, false);
        metrics.recordDecisionCache(true);
        metrics.recordAction(true, true, false, "success");
        CognitionMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(0.75, snapshot.providerCacheHitRate(), 0.0001);
        assertEquals(1, snapshot.successfulActions());
        assertEquals(1, snapshot.decisionCacheHits());
    }
}
