package com.mineagent.engine.identity;

import com.mineagent.api.config.MineAgentConfig;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.loop.AgentLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual loop's reflection gate and the engine's server configuration selection. */
final class IdentityReflectionConfiguredTest {
    private static void configure(int interval) throws Exception {
        var previous = MineAgentEngine.getConfig();
        var next = new MineAgentConfig(previous.llm(), previous.companion(), previous.survival(),
                previous.pathfinding(), previous.language(), previous.safety(), previous.vision(),
                new MineAgentConfig.ReflectionConfig(interval));
        apply(next);
    }
    private static void apply(MineAgentConfig config) throws Exception {
        var method = MineAgentEngine.class.getDeclaredMethod("applyConfiguration", MineAgentConfig.class);
        method.setAccessible(true); method.invoke(null, config);
    }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"-1,1200", "1,200", "1200,1200", "999999,72000"})
    void defaultAndBoundedServerIntervalsReachTheActualGate(int configured, int expected) throws Exception {
        var previous = MineAgentEngine.getConfig();
        try {
            configure(configured);
            var loop = new AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var identity = (EvolvingIdentity) field(loop, "identity");
            var gate = (IdentityReflection) field(loop, "identityReflection");
            identity.recordEvidence("observation", "executor", "first physical result", 1);
            assertTrue(gate.open(10, 1, false));
            gate.close();
            identity.recordEvidence("observation", "executor", "second physical result", 11);
            assertFalse(gate.open(10 + expected - 1, 2, false));
            assertTrue(gate.open(10 + expected, 2, false));
        } finally { apply(previous); }
    }

    @Test void reloadAndPersistenceRetainEvidenceGateAndMutationBudget() throws Exception {
        var previous = MineAgentEngine.getConfig();
        try {
            configure(200);
            var loop = new AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var identity = (EvolvingIdentity) field(loop, "identity");
            var gate = (IdentityReflection) field(loop, "identityReflection");
            var evidence = identity.recordEvidence("observation", "executor", "observed physical result", 1);
            assertFalse(gate.open(10, 1, true));
            assertTrue(gate.open(10, 1, false));
            for (int i = 0; i < 2; i++) gate.revise(1, "world", "observed subject " + i,
                    "observed state", "observed physical result", java.util.List.of(evidence.id()), 0.6, 0, "");
            assertThrows(IllegalArgumentException.class, () -> gate.revise(1, "world", "excess subject",
                    "observed state", "observed physical result", java.util.List.of(evidence.id()), 0.6, 0, ""));
            assertEquals(2, identity.entries().size());
            var restored = new AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var restoredIdentity = (EvolvingIdentity) field(restored, "identity");
            var restoredGate = (IdentityReflection) field(restored, "identityReflection");
            restoredIdentity.importState(identity.exportState());
            restoredGate.importState(gate.exportState());
            assertEquals(identity.exportState(), restoredIdentity.exportState());
            assertEquals("", restoredGate.context("en_us", 1000));
            assertFalse(restoredGate.open(210, 2, false));
            restoredIdentity.recordEvidence("observation", "executor", "new physical result", 211);
            configure(1200);
            assertFalse(restoredGate.open(210, 2, false));
            assertTrue(restoredGate.open(1210, 2, false));
            assertThrows(IllegalArgumentException.class, () -> restoredGate.revise(1, "world", "stale generation",
                    "observed state", "observed physical result", java.util.List.of(evidence.id()), 0.6, 0, ""));
        } finally { apply(previous); }
    }

    @Test void existingLoopReadsCustomIntervalAfterServerReload() throws Exception {
        var previous = MineAgentEngine.getConfig();
        try {
            configure(1200);
            var loop = new AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var identity = (EvolvingIdentity) field(loop, "identity");
            var gate = (IdentityReflection) field(loop, "identityReflection");
            identity.recordEvidence("observation", "executor", "first result", 1);
            assertTrue(gate.open(10, 1, false));
            gate.close();
            identity.recordEvidence("observation", "executor", "second result", 11);
            assertFalse(gate.open(209, 2, false));
            configure(200); // Same loop and persisted gate state, not a fresh default instance.
            assertFalse(gate.open(209, 2, false));
            assertTrue(gate.open(210, 2, false));
            gate.close();
            identity.recordEvidence("observation", "executor", "third result", 211);
            configure(2400);
            assertFalse(gate.open(1410, 3, false));
            assertTrue(gate.open(2610, 3, false));
        } finally { apply(previous); }
    }

    @Test void reloadDisableRevokesAlreadyOpenMutationWindow() throws Exception {
        var previous = MineAgentEngine.getConfig();
        try {
            configure(200);
            var loop = new AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var identity = (EvolvingIdentity) field(loop, "identity");
            var gate = (IdentityReflection) field(loop, "identityReflection");
            var evidence = identity.recordEvidence("observation", "executor", "physical state changed", 1);
            assertTrue(gate.open(2, 1, false));
            configure(0);
            assertThrows(IllegalArgumentException.class, () -> gate.revise(1, "world", "observed subject",
                    "observed state", "observed change", java.util.List.of(evidence.id()), 0.6, 0, ""));
            assertTrue(identity.entries().isEmpty());
            assertEquals("", gate.context("pt_br", 1000));
            configure(200);
            assertFalse(gate.open(201, 2, false));
            identity.recordEvidence("observation", "executor", "another physical result", 202);
            assertTrue(gate.open(202, 2, false));
        } finally { apply(previous); }
    }

    @Test void serverDisableClosesActualLoopGateWithoutConsumingEvidence() throws Exception {
        var previous = MineAgentEngine.getConfig();
        try {
            configure(0);
            var loop = new AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var identity = (EvolvingIdentity) field(loop, "identity");
            var gate = (IdentityReflection) field(loop, "identityReflection");
            identity.recordEvidence("observation", "executor", "observed result", 1);
            var before = gate.exportState();
            assertFalse(gate.open(2, 1, false));
            assertEquals(before, gate.exportState());
            assertEquals("", gate.context("en_us", 1000));
        } finally { apply(previous); }
    }
}
