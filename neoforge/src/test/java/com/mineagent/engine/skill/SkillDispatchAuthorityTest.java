package com.mineagent.engine.skill;

import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real runtime and reliability counters across reentrant dispatch boundaries. */
final class SkillDispatchAuthorityTest {
    private static SkillLibrary library() {
        var library = new SkillLibrary();
        library.registerSequence("authority", "verified execution", "execution",
                "[{\"tool\":\"craft\",\"args\":{},\"on_failure\":\"retry_once\"}]", true);
        return library;
    }

    @Test void revokedLoopDispatchDoesNotTrainReliability() throws Exception {
        var loop = new com.mineagent.engine.loop.AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
        var field = loop.getClass().getDeclaredField("suspended"); field.setAccessible(true); field.set(loop, true);
        field = loop.getClass().getDeclaredField("skillLib"); field.setAccessible(true);
        var library = (SkillLibrary) field.get(loop);
        library.registerSequence("authority", "verified execution", "execution",
                "[{\"tool\":\"craft\",\"args\":{},\"on_failure\":\"retry_once\"}]", true);
        var before = library.get("authority").orElseThrow();
        field = loop.getClass().getDeclaredField("skillRuntime"); field.setAccessible(true);
        var runtime = (SkillRuntime) field.get(loop);
        var priorTools = new java.util.ArrayList<>(com.mineagent.api.agent.tool.ToolRegistry.all());
        try {
            com.mineagent.api.agent.tool.ToolRegistry.clear();
            com.mineagent.api.agent.tool.ToolRegistry.register(new com.mineagent.tools.crafting.CraftTool());
            assertTrue(runtime.start("authority", null, 1).accepted());
            runtime.tick(2); // No active loop lease: the real dispatcher must revoke, never learn failure.
            assertFalse(runtime.active());
            assertEquals(before, library.get("authority").orElseThrow());
        } finally {
            com.mineagent.api.agent.tool.ToolRegistry.clear();
            priorTools.forEach(com.mineagent.api.agent.tool.ToolRegistry::register);
        }
    }

    @Test void cancellationCannotInvalidateAnAdapterEvenWhenEvidenceContainsPostcondition() throws Exception {
        var loop = new com.mineagent.engine.loop.AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
        var suspended = loop.getClass().getDeclaredField("suspended"); suspended.setAccessible(true); suspended.set(loop, true);
        var library = (SkillLibrary) field(loop, "skillLib");
        var world = (SemanticWorldModel) field(loop, "semanticWorldModel");
        var explorer = (com.mineagent.engine.exploration.MechanismExplorer) field(loop, "mechanismExplorer");
        for (int i = 1; i <= 2; i++) {
            long tick = i * 100L;
            String subject = "machine:" + i;
            assertTrue(explorer.propose("mod:unknown", "observed transition", "interact_at", "{}", subject,
                    "state", "active", com.mineagent.engine.exploration.MechanismExplorer.Risk.MEDIUM,
                    "context:" + i, true, "close_gui", "{}", 4, 0.8, tick).accepted());
            String call = "call_exact_" + i;
            explorer.onToolDispatched(call, "interact_at", "{}", tick + 1);
            world.observe(subject, "state", "active", null, 0.9, "inspect_gui", call, tick + 2, 40, false);
            explorer.onToolResult(call, "interact_at", true, false, "observed", tick + 2);
        }
        var rule = explorer.knowledgeBase().rules().iterator().next();
        var before = library.get(rule.adapterSkill()).orElseThrow();
        var runtime = (SkillRuntime) field(loop, "skillRuntime");
        assertTrue(runtime.start(rule.adapterSkill(), null, 300).accepted());
        runtime.cancel("owner text mentions postcondition; not a physical contradiction", 301);
        assertEquals(before, library.get(rule.adapterSkill()).orElseThrow());
        assertEquals(rule, explorer.knowledgeBase().rules().iterator().next());
        assertTrue(world.exportState().events().stream().noneMatch(e -> e.subject().equals("tool:execute_skill")));
    }

    @Test void verifiedActionFailureCannotInvalidateAdapterFromQuotedPostconditionText() throws Exception {
        var loop = new com.mineagent.engine.loop.AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
        var suspended = loop.getClass().getDeclaredField("suspended"); suspended.setAccessible(true); suspended.set(loop, true);
        var library = (SkillLibrary) field(loop, "skillLib");
        var world = (SemanticWorldModel) field(loop, "semanticWorldModel");
        var explorer = (com.mineagent.engine.exploration.MechanismExplorer) field(loop, "mechanismExplorer");
        for (int i = 1; i <= 2; i++) {
            long tick = i * 100L;
            String subject = "machine:" + i;
            assertTrue(explorer.propose("mod:unknown", "observed transition", "interact_at", "{}", subject,
                    "state", "active", com.mineagent.engine.exploration.MechanismExplorer.Risk.MEDIUM,
                    "context:" + i, true, "close_gui", "{}", 4, 0.8, tick).accepted());
            String call = "call_exact_" + i;
            explorer.onToolDispatched(call, "interact_at", "{}", tick + 1);
            world.observe(subject, "state", "active", null, 0.9, "inspect_gui", call, tick + 2, 40, false);
            explorer.onToolResult(call, "interact_at", true, false, "observed", tick + 2);
        }
        var rule = explorer.knowledgeBase().rules().iterator().next();
        var callback = loop.getClass().getDeclaredMethod("onSkillRuntimeCompleted", SkillRuntime.Snapshot.class);
        callback.setAccessible(true);
        var runtime = new SkillRuntime(library, world,
                (id, tool, args) -> new SkillRuntime.DispatchResult(false, false, false, "target refused; quoted postcondition text"),
                result -> { try { callback.invoke(loop, result); } catch (Exception e) { throw new AssertionError(e); } });
        runtime.start(rule.adapterSkill(), null, 300);
        runtime.tick(301);
        assertEquals(2, library.get(rule.adapterSkill()).orElseThrow().invocations());
        assertEquals(0.5, library.get(rule.adapterSkill()).orElseThrow().successRate());
        assertEquals(rule, explorer.knowledgeBase().rules().iterator().next());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void verifiedMissingEffectStillInvalidatesAdapterInBothLanguages(String language) throws Exception {
        String previous = com.mineagent.api.i18n.RuntimeLanguage.language().id();
        try {
            com.mineagent.api.i18n.RuntimeLanguage.configure(language);
            var loop = new com.mineagent.engine.loop.AgentLoop(null, "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            var suspended = loop.getClass().getDeclaredField("suspended"); suspended.setAccessible(true); suspended.set(loop, true);
            var library = (SkillLibrary) field(loop, "skillLib");
            var world = (SemanticWorldModel) field(loop, "semanticWorldModel");
            var explorer = (com.mineagent.engine.exploration.MechanismExplorer) field(loop, "mechanismExplorer");
            for (int i = 1; i <= 2; i++) {
                long tick = i * 100L;
                assertTrue(explorer.propose("mod:unknown", "observed transition", "interact_at", "{}", "machine:" + i,
                        "state", "active", com.mineagent.engine.exploration.MechanismExplorer.Risk.MEDIUM,
                        "context:" + i, true, "close_gui", "{}", 4, 0.8, tick).accepted());
                String action = "call_exact_" + i;
                explorer.onToolDispatched(action, "interact_at", "{}", tick + 1);
                world.observe("machine:" + i, "state", "active", null, 0.9, "inspect_gui", action, tick + 2, 40, false);
                explorer.onToolResult(action, "interact_at", true, false, "observed", tick + 2);
            }
            var rule = explorer.knowledgeBase().rules().iterator().next();
            var before = library.get(rule.adapterSkill()).orElseThrow();
            var callback = loop.getClass().getDeclaredMethod("onSkillRuntimeCompleted", SkillRuntime.Snapshot.class);
            callback.setAccessible(true);
            var learnedAtTerminal = new AtomicReference<SkillLibrary.Skill>();
            var runtime = new SkillRuntime(library, world,
                    (id, tool, args) -> new SkillRuntime.DispatchResult(true, false, true, "physical act accepted"),
                    result -> {
                        learnedAtTerminal.set(library.get(rule.adapterSkill()).orElseThrow());
                        try { callback.invoke(loop, result); } catch (Exception e) { throw new AssertionError(e); }
                    });
            assertTrue(runtime.start(rule.adapterSkill(), null, 300).accepted());
            runtime.tick(301);
            assertEquals(SkillRuntime.Status.VERIFYING, runtime.snapshot().status());
            runtime.tick(1000);
            assertEquals(SkillRuntime.FailureKind.POSTCONDITION, runtime.snapshot().failureKind());
            assertEquals(SkillRuntime.Disposition.VERIFIED, runtime.snapshot().disposition());
            assertEquals(before.invocations() + 1, learnedAtTerminal.get().invocations());
            assertTrue(library.get(rule.adapterSkill()).isEmpty(), "contradicted adapter must no longer be replayable");
            assertEquals(rule.refutations() + 1, explorer.knowledgeBase().rules().iterator().next().refutations());
        } finally { com.mineagent.api.i18n.RuntimeLanguage.configure(previous); }
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tool_cancelled", "tool_timeout"})
    void legacyStructuredUncertaintyPreservesCountersInBothLanguages(String code) {
        String previous = com.mineagent.api.i18n.RuntimeLanguage.language().id();
        try {
            for (String language : java.util.List.of("en_us", "pt_br")) {
                com.mineagent.api.i18n.RuntimeLanguage.configure(language);
                var library = library();
                var before = library.get("authority").orElseThrow();
                String receipt = "{\"error\":\"literal unrelated evidence\",\"code\":\"" + code + "\",\"crafted_batches\":1}";
                var runtime = new SkillRuntime(library, new SemanticWorldModel(),
                        (id, tool, args) -> new SkillRuntime.DispatchResult(false, false, false, receipt), result -> {});
                runtime.start("authority", null, 1); runtime.tick(2); runtime.tick(3);
                assertFalse(runtime.active());
                assertFalse(runtime.snapshot().disposition().learnable());
                assertEquals(receipt, runtime.snapshot().lastEvidence());
                assertEquals(0, runtime.snapshot().stepIndex());
                assertEquals(1, runtime.snapshot().attempt());
                assertEquals(before, library.get("authority").orElseThrow());
            }
        } finally { com.mineagent.api.i18n.RuntimeLanguage.configure(previous); }
    }

    @Test void progressIsNotATerminalPhysicalReceipt() {
        var library = library();
        var before = library.get("authority").orElseThrow();
        var action = new AtomicReference<String>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), (id, tool, args) -> {
            action.set(id); return new SkillRuntime.DispatchResult(true, true, true, "async admission");
        }, result -> {});
        runtime.start("authority", null, 1); runtime.tick(2);
        assertFalse(runtime.onTaskFinished(action.get(), com.mineagent.api.task.TaskState.RUNNING, "progress", 3));
        assertFalse(runtime.onTaskFinished(action.get(), null, "unknown", 3));
        assertEquals(SkillRuntime.Status.WAITING_TASK, runtime.snapshot().status());
        assertEquals(before, library.get("authority").orElseThrow());
    }

    @Test void failureCleanupCannotPublishAnotherCancellationTerminal() {
        var library = library();
        var completed = new ArrayList<SkillRuntime.Snapshot>();
        var ref = new AtomicReference<SkillRuntime>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), new SkillRuntime.ActionDispatcher() {
            public SkillRuntime.DispatchResult dispatch(String id, String tool, com.google.gson.JsonObject args) {
                return new SkillRuntime.DispatchResult(false, false, false, "verified refusal");
            }
            public void cancel(String id) { ref.get().cancel("cleanup callback", 3); }
        }, completed::add);
        ref.set(runtime);
        runtime.start("authority", null, 1);
        runtime.tick(2);
        assertEquals(1, completed.size());
        assertEquals(SkillRuntime.Status.NEEDS_REPLAN, completed.getFirst().status());
        assertEquals(SkillRuntime.Disposition.VERIFIED, completed.getFirst().disposition());
        assertEquals(2, library.get("authority").orElseThrow().invocations());
        assertEquals(0.5, library.get("authority").orElseThrow().successRate());
    }

    @Test void cleanupCannotReplaceTerminalSnapshotWithReentrantlyStartedRun() {
        var library = library();
        var completed = new ArrayList<SkillRuntime.Snapshot>();
        var ref = new AtomicReference<SkillRuntime>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), new SkillRuntime.ActionDispatcher() {
            public SkillRuntime.DispatchResult dispatch(String id, String tool, com.google.gson.JsonObject args) {
                ref.get().cancel("owner revoked", 3);
                return new SkillRuntime.DispatchResult(true, false, true, "late physical receipt");
            }
            public void cancel(String id) {
                assertTrue(ref.get().start("authority", null, 4).accepted());
                throw new IllegalStateException("cleanup failure from previous run");
            }
        }, completed::add);
        ref.set(runtime);
        String oldRun = runtime.start("authority", null, 1).runId();
        runtime.tick(2);
        assertEquals(1, completed.size());
        assertEquals(oldRun, completed.getFirst().runId());
        assertEquals(SkillRuntime.Status.CANCELLED, completed.getFirst().status());
        assertNotEquals(oldRun, runtime.snapshot().runId());
        assertFalse(runtime.snapshot().lastEvidence().contains("cleanup failure from previous run"));
        assertEquals(SkillRuntime.Status.RUNNING, runtime.snapshot().status());
        assertEquals(0, runtime.snapshot().stepIndex());
        assertEquals(1, library.get("authority").orElseThrow().invocations());
    }

    @Test void thrownDispatcherLeavesPhysicalOutcomeUnverified() {
        var library = library();
        var before = library.get("authority").orElseThrow();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), (id, tool, args) -> {
            throw new IllegalStateException("callback lost after partial work");
        }, result -> {});
        runtime.start("authority", null, 1);
        runtime.tick(2);
        assertEquals(SkillRuntime.Disposition.UNVERIFIED, runtime.snapshot().disposition());
        assertEquals(before, library.get("authority").orElseThrow());
    }

    @Test void elapsedAsyncDeadlineStopsUnverifiedWithoutRetryOrLearning() {
        var library = library();
        var before = library.get("authority").orElseThrow();
        var completed = new ArrayList<SkillRuntime.Snapshot>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(),
                (id, tool, args) -> new SkillRuntime.DispatchResult(true, true, true, "async admission"), completed::add);
        runtime.start("authority", null, 1);
        runtime.tick(2);
        runtime.tick(6403);
        assertFalse(runtime.active());
        assertEquals(SkillRuntime.Disposition.UNVERIFIED, runtime.snapshot().disposition());
        assertEquals(before, library.get("authority").orElseThrow());
        assertEquals(1, completed.size());
    }

    @Test void cancelledTaskReceiptDoesNotBecomeLearnedFailureOrRetry() {
        var library = library();
        var before = library.get("authority").orElseThrow();
        var action = new AtomicReference<String>();
        var completed = new ArrayList<SkillRuntime.Snapshot>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), (id, tool, args) -> {
            action.set(id);
            return new SkillRuntime.DispatchResult(true, true, true, "async admission");
        }, completed::add);
        runtime.start("authority", null, 1);
        runtime.tick(2);
        assertTrue(runtime.onTaskFinished(action.get(), com.mineagent.api.task.TaskState.CANCELLED,
                "cancelled with partial physical receipts", 3));
        assertEquals(SkillRuntime.Status.CANCELLED, runtime.snapshot().status());
        assertEquals(SkillRuntime.Disposition.CANCELLED, runtime.snapshot().disposition());
        assertEquals(before, library.get("authority").orElseThrow());
        assertEquals(1, completed.size());
        assertEquals("cancelled with partial physical receipts", completed.getFirst().lastEvidence());
    }

    @Test void synchronousTerminalCallbackIsConsumedOnce() {
        var library = library();
        var ref = new AtomicReference<SkillRuntime>();
        var completed = new ArrayList<SkillRuntime.Snapshot>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), (id, tool, args) -> {
            assertTrue(ref.get().onTaskFinished(id, com.mineagent.api.task.TaskState.SUCCESS, "physical receipt", 3));
            return new SkillRuntime.DispatchResult(true, true, true, "async admission");
        }, completed::add);
        ref.set(runtime);
        runtime.start("authority", null, 1);
        runtime.tick(2);
        assertEquals(SkillRuntime.Status.SUCCEEDED, runtime.snapshot().status());
        assertEquals(1, completed.size());
        assertEquals(2, library.get("authority").orElseThrow().invocations());
        assertFalse(runtime.onTaskFinished("obsolete", com.mineagent.api.task.TaskState.SUCCESS, "duplicate", 4));
    }

    @Test void cancelledDispatchCannotConsumeSuccessfulLateResult() {
        var library = library();
        var before = library.get("authority").orElseThrow();
        var ref = new AtomicReference<SkillRuntime>();
        var completed = new ArrayList<SkillRuntime.Snapshot>();
        var runtime = new SkillRuntime(library, new SemanticWorldModel(), (id, tool, args) -> {
            ref.get().cancel("owner revoked", 3);
            return new SkillRuntime.DispatchResult(true, false, true,
                    "{\"success\":true,\"crafted_batches\":1}");
        }, completed::add);
        ref.set(runtime);
        assertTrue(runtime.start("authority", null, 1).accepted());
        runtime.tick(2);
        assertEquals(SkillRuntime.Status.CANCELLED, runtime.snapshot().status());
        assertEquals(0, runtime.snapshot().stepIndex());
        assertEquals(1, completed.size());
        assertEquals(before, library.get("authority").orElseThrow());
    }
}
