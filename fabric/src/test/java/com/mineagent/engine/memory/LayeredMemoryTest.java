package com.mineagent.engine.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LayeredMemoryTest {
    @Test
    void recallIsBoundedAndKeepsEvidenceTiersSeparate() {
        LayeredMemory memory = new LayeredMemory();
        memory.remember(LayeredMemory.Tier.WORKING, "station", "crafting table at 1,64,1",
                "scan", 0.9, 10, 100, "vanilla", "overworld");
        memory.remember(LayeredMemory.Tier.SEMANTIC, "station.rule", "reuse observed table",
                "verified", 0.95, 10, 0, "vanilla", "overworld");
        var recalled = memory.recall("crafting table station", 12, 20);
        assertFalse(recalled.isEmpty());
        assertTrue(recalled.size() <= 2);
        assertTrue(memory.summarizeForPrompt("station", 12, 20).contains("Layered memory"));
    }

    @Test
    void expiredWorkingMemoryIsRemovedDuringConsolidation() {
        LayeredMemory memory = new LayeredMemory();
        memory.remember(LayeredMemory.Tier.WORKING, "temporary", "old", "scan",
                0.8, 0, 5, "unknown", "overworld");
        memory.consolidate(10);
        assertTrue(memory.recall("temporary", 10, 100).isEmpty());
    }


    @Test
    void unrelatedHighConfidenceMemoryDoesNotConsumePromptBudget() {
        LayeredMemory memory = new LayeredMemory();
        memory.remember(LayeredMemory.Tier.SEMANTIC, "furnace.rule", "smelt iron ore",
                "verified", 1.0, 10, 0, "vanilla", "overworld");
        assertTrue(memory.recall("find a jungle biome", 11, 100).isEmpty());
    }

    @Test
    void shortChineseQueriesStillMatchHanNgrams() {
        LayeredMemory memory = new LayeredMemory();
        memory.remember(LayeredMemory.Tier.EPISODIC, "工作台位置", "工作台在出生点北侧",
                "scan", 0.9, 10, 1_000, "vanilla", "overworld");
        assertFalse(memory.recall("工作台", 11, 100).isEmpty());
    }

    @Test
    void recallDoesNotPretendThatReadingVerifiedTheFactAgain() {
        LayeredMemory memory = new LayeredMemory();
        memory.remember(LayeredMemory.Tier.EPISODIC, "station", "table at 1,64,1",
                "scan", 0.9, 10, 10_000, "vanilla", "overworld");
        var first = memory.recall("station table", 100, 100);
        assertEquals(10, first.get(0).lastVerifiedTick());
        var second = memory.recall("station table", 5_000, 100);
        assertEquals(10, second.get(0).lastVerifiedTick());
        assertEquals(2, second.get(0).accesses());
    }
}
