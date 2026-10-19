package com.mineagent.engine.i18n;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.reflex.Reflex;
import com.mineagent.api.task.reflex.ReflexRegistry;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.memory.ExperienceStore;
import com.mineagent.engine.survival.reflex.PickupItemsReflex;
import com.mineagent.engine.task.TaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.util.List;
import static com.mineagent.engine.i18n.SchedulerLanguageTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerDiagnosticLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest @MethodSource("diagnosticCases")
    void realSchedulerFailureAndSnapshotFallbackKeepTheActualDiagnosis(String language, String opaque, ExperienceStore.FailureKind expected) {
        RuntimeLanguage.configure(language);
        try (var f = new Fixture()) {
            var task = f.task("diagnosis:{0}:ç");
            f.auction.submitTask(task);
            task.tickError = new IllegalStateException(opaque);
            capture(true, f.auction::tick);
            var info = f.info(task);
            assertTrue(info.message.endsWith("IllegalStateException: " + String.valueOf(opaque)));
            assertEquals(expected, ExperienceStore.diagnose(info.state, info.snapshot, info.message));
            assertEquals(ExperienceStore.diagnose(TaskState.FAILED, task.snapshot(), "Task failed with IllegalStateException: " + opaque),
                    ExperienceStore.diagnose(info.state, info.snapshot, info.message));
        }
        try (var f = new Fixture()) {
            var task = f.task("snapshot-diagnosis:{1}:ã");
            f.auction.submitTask(task);
            task.snapshotError = new IllegalStateException(opaque);
            f.auction.now++;
            f.auction.tick();
            var info = f.info(task);
            assertEquals("snapshot_error", info.snapshot.stage());
            assertTrue(info.snapshot.blockedReason().endsWith(String.valueOf(opaque)));
            var snapshotKind = expected == ExperienceStore.FailureKind.EXECUTION_ERROR ? ExperienceStore.FailureKind.UNKNOWN : expected;
            assertEquals(snapshotKind, ExperienceStore.diagnose(TaskState.FAILED, info.snapshot, info.message));
        }
    }

    static Stream<Arguments> diagnosticCases() {
        return Stream.of("en_us", "pt_br").flatMap(language -> Stream.of(
                Arguments.of(language, "timeout: id=mod:ação {0} $1", ExperienceStore.FailureKind.TIMEOUT),
                Arguments.of(language, "navigation: unreachable target=(4,-5,6)", ExperienceStore.FailureKind.NAVIGATION),
                Arguments.of(language, "precondition: required item mod:ação {1}", ExperienceStore.FailureKind.PRECONDITION),
                Arguments.of(language, "target gone: mod:ação", ExperienceStore.FailureKind.TARGET_CHANGED),
                Arguments.of(language, "refused: mod:ação {0}", ExperienceStore.FailureKind.INVALID_ACTION),
                Arguments.of(language, "recipe: mod:ação {1}", ExperienceStore.FailureKind.WORLD_RULE_MISMATCH),
                Arguments.of(language, "opaque:{0}:ç $1 \\n {\\\"nested\\\":\\\"mod:ação\\\"}", ExperienceStore.FailureKind.EXECUTION_ERROR),
                Arguments.of(language, null, ExperienceStore.FailureKind.EXECUTION_ERROR)));
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "pt_br"})
    void chainAndTaskErrorLogsTranslateOnlyTheTrustedWrapper(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        String opaque = "opaque:{0}:ç $1 navigation timeout";
        try (var f = new Fixture()) {
            var task = f.task("log:{0}");
            f.auction.submitTask(task);
            f.chain.priorityError = new IllegalStateException(opaque);
            assertEquals("[MineAgent] " + (pt ? "Erro de prioridade da cadeia " + f.chain.name : "Chain " + f.chain.name + " priority error") + ": " + opaque + System.lineSeparator(), capture(true, f.auction::tick));
            assertEquals(1, task.ticks);
            f.chain.priorityError = null;
            f.chain.priority = 10;
            f.chain.tickError = new IllegalStateException(opaque);
            String error = capture(true, () -> capture(false, f.auction::tick));
            assertEquals("[MineAgent] " + (pt ? "Erro de tick da cadeia " + f.chain.name : "Chain " + f.chain.name + " tick error") + ": " + opaque + System.lineSeparator(), error);
            assertNull(f.auction.currentWinner());
            assertTrue(f.auction.hasRunningTask());
            assertEquals(1, task.ticks);
            assertEquals(1, f.chain.interrupts);
        }
        try (var f = new Fixture()) {
            f.chain.priority = 10;
            f.auction.tick();
            f.chain.priority = Float.NEGATIVE_INFINITY;
            f.chain.interruptError = new IllegalStateException(opaque);
            var task = f.task("cleanup:{1}");
            f.auction.submitTask(task);
            assertEquals("[MineAgent] " + (pt ? "Erro ao interromper cadeia" : "Chain interrupt error") + ": " + opaque + System.lineSeparator(), capture(true, f.auction::tick));
            task.tickError = new IllegalStateException(opaque);
            task.interruptError = new IllegalStateException(opaque);
            String errors = capture(true, f.auction::tick);
            assertEquals("[MineAgent] " + (pt ? "Erro de tick da tarefa" : "Task tick error") + ": " + opaque + System.lineSeparator()
                    + "[MineAgent] " + (pt ? "Erro na limpeza da tarefa" : "Task cleanup error") + ": " + opaque + System.lineSeparator(), errors);
            assertEquals(TaskState.FAILED, f.info(task).state);
            assertEquals(1, f.loop.finished);
            assertFalse(f.auction.hasRunningTask());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "pt_br"})
    void idleAndReflexErrorsStayBestEffortAndKeepExternalText(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        var previous = List.copyOf(ReflexRegistry.all());
        ReflexRegistry.clear();
        try (var f = new Fixture()) {
            ReflexRegistry.register(new Reflex() {
                public String id() { return "literal_reflex"; }
                public String description() { return "fixture"; }
                public boolean isEnabled(AgentPlayer body) { throw new IllegalStateException("opaque:{0}:ç"); }
                public void enable(AgentPlayer body) {}
                public void disable(AgentPlayer body) {}
            });
            assertEquals("[MineAgent] " + (pt ? "Erro de ociosidade/reflexo" : "Idle/reflex error") + ": opaque:{0}:ç" + System.lineSeparator(), capture(true, f.auction::tick));
            assertFalse(f.auction.hasRunningTask());
            ReflexRegistry.clear();
            ReflexRegistry.register(new PickupItemsReflex());
            var body = new CompanionEntity(null, null, "fixture") {
                int calls;
                @Override public java.util.UUID companionId() {
                    if (++calls == 2) throw new IllegalStateException("external:{1}:ã");
                    return super.companionId();
                }
            };
            try {
                var auction = new ClockAuction(body, f.loop, List.of());
                assertEquals("[MineAgent] " + (pt ? "Erro de tick do reflexo pickup_items" : "Reflex pickup_items tick error") + ": external:{1}:ã" + System.lineSeparator(), capture(true, auction::tick));
                assertFalse(auction.hasRunningTask());
            } finally { TaskContext.removeCaches(body); }
        } finally {
            ReflexRegistry.clear(); previous.forEach(ReflexRegistry::register);
        }
    }
}
