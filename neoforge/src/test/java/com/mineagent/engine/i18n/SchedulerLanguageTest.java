package com.mineagent.engine.i18n;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.*;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.scheduler.PriorityAuction;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.tools.management.TaskStatusTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void englishAdmissionBaselineUsesRealSchedulerAndLedger() {
        RuntimeLanguage.configure("en_us");
        try (var f = new Fixture()) {
            var task = f.task("active:{0}:ç");
            assertEquals(new PriorityAuction.TaskAdmission(true, task.record().toolCallId(), "Accepted"), f.auction.submitTask(task));
            assertEquals(1, task.starts);
            assertEquals("Executor reports success", f.loop.intent.successCriterion());
            assertEquals(TaskState.RUNNING, f.info(task).state);
            assertEquals(task.snapshot(), f.info(task).snapshot);
            assertEquals(1, f.loop.accepted);
            assertEquals(List.of("Probe"), f.auction.ui);
            f.auction.tick();
            assertEquals(1, task.ticks);
            assertTrue(f.auction.hasRunningTask());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void admissionLocalizesOnlyGeneratedProseAndKeepsBusyWork(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        try (var f = new Fixture()) {
            var active = f.task("active:{0}:ç");
            active.atStart = () -> assertEquals(pt ? "Em execução" : "Running", f.info(active).message);
            var admitted = f.auction.submitTask(active);
            assertEquals(pt ? "Aceita" : "Accepted", admitted.message());
            assertEquals(pt ? "O executor informa sucesso" : "Executor reports success", f.loop.intent.successCriterion());
            var incoming = f.task("incoming:{1}:ã");
            var rejected = f.auction.submitTask(incoming);
            String expected = pt
                    ? "O corpo está ocupado com a tarefa 'active:{0}:ç'; aguarde a conclusão ou chame task_stop antes de despachar outra tarefa corporal"
                    : "Body is busy with task 'active:{0}:ç'; wait for completion or call task_stop before dispatching another body task";
            assertEquals(new PriorityAuction.TaskAdmission(false, "active:{0}:ç", expected), rejected);
            assertEquals(TaskState.CANCELLED, f.info(incoming).state);
            assertEquals(expected, f.info(incoming).message);
            assertEquals(expected, f.loop.message);
            assertEquals("incoming:{1}:ã", f.loop.taskId);
            assertEquals("[TASK_FINISHED] task_id=incoming:{1}:ã state=CANCELLED message=" + expected, f.loop.logs.getLast());
            assertEquals(0, incoming.starts);
            assertEquals(0, active.interrupts);
            assertTrue(f.auction.hasRunningTask());
            assertFalse(f.auction.cancelTask("incoming:{1}:ã"));
            assertEquals(TaskState.RUNNING, f.info(active).state);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void lifecycleErrorsLocalizeTheirWrapperWithoutChangingOpaqueDiagnostics(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        try (var f = new Fixture()) {
            var task = f.task("tick:{1}:ç");
            f.auction.submitTask(task);
            task.tickError = new IllegalStateException("navigation: target=mod:ação {0} $1 \\n timeout");
            f.auction.now += 3;
            capture(true, f.auction::tick);
            String expected = (pt ? "A tarefa falhou com " : "Task failed with ") + "IllegalStateException: " + task.tickError.getMessage();
            assertEquals(expected, f.info(task).message);
            assertEquals(expected, f.loop.message);
            assertEquals(TaskState.FAILED, f.info(task).state);
            assertEquals(3, f.info(task).elapsedTicks);
            assertEquals(1, task.interrupts);
            assertFalse(f.auction.hasRunningTask());
            assertEquals("[TASK_FINISHED] task_id=tick:{1}:ç state=FAILED message=" + expected, f.loop.logs.getLast());
        }
        try (var f = new Fixture()) {
            var task = f.task("start:{0}");
            task.startError = new IllegalArgumentException((String) null);
            assertSame(task.startError, assertThrows(IllegalArgumentException.class, () -> f.auction.submitTask(task)));
            assertEquals((pt ? "A tarefa falhou com " : "Task failed with ") + "IllegalArgumentException: null", f.info(task).message);
            assertEquals(0, f.loop.accepted);
            assertEquals(1, f.loop.finished);
            assertEquals(1, task.interrupts);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void brokenCompletionUsesLocalizedFallbackButRetainsTerminalState(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        for (var state : List.of(TaskState.SUCCESS, TaskState.FAILED, TaskState.CANCELLED, TaskState.PENDING, TaskState.PAUSED)) {
            try (var f = new Fixture()) {
                var task = f.task("fallback:" + state);
                task.completionError = true;
                f.auction.submitTask(task);
                if (state == TaskState.CANCELLED) assertTrue(f.auction.cancelTask(task.record().toolCallId()));
                else { task.nextState = state; f.auction.tick(); }
                String expected = pt ? switch (state) {
                    case SUCCESS -> "sucesso";
                    case FAILED -> "falhou";
                    case CANCELLED -> "cancelada";
                    case PENDING -> "pendente";
                    case PAUSED -> "pausada";
                    default -> throw new AssertionError(state);
                } : state.name().toLowerCase(java.util.Locale.ROOT);
                assertEquals(expected, f.info(task).message);
                assertEquals(state, f.info(task).state);
                assertTrue(f.loop.logs.getLast().contains(" state=" + state.name() + " message=" + expected));
                assertEquals(1, f.loop.finished);
                assertEquals(1, task.interrupts);
                assertFalse(f.auction.hasRunningTask());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void progressFallbacksAreLocalizedAtTheProducerWithoutRewritingSnapshots(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        try (var f = new Fixture()) {
            var task = f.task("progress:{0}:ç");
            task.blocker = "timeout navigation: mod:alvo {1}";
            f.auction.submitTask(task);
            var literal = task.snapshot();
            task.nullSnapshot = true;
            f.auction.now++;
            f.auction.tick();
            assertEquals(TaskSnapshot.running("running", pt ? "Probe está em execução" : "Probe is running"), f.info(task).snapshot);
            assertEquals(f.info(task).snapshot.summary(), f.loop.message);
            task.nullSnapshot = false;
            task.snapshotError = new IllegalStateException("timeout navigation: mod:alvo {1} $2");
            f.auction.now++;
            f.auction.tick();
            var fallback = f.info(task).snapshot;
            assertEquals("snapshot_error", fallback.stage());
            assertEquals("Probe", fallback.summary());
            assertEquals((pt ? "Falha no snapshot de progresso: " : "Progress snapshot failed: ") + task.snapshotError.getMessage(), fallback.blockedReason());
            assertEquals(0, fallback.completedUnits());
            assertEquals(-1, fallback.totalUnits());
            assertNull(fallback.evidence());
            assertNull(fallback.targetX());
            task.snapshotError = null;
            f.auction.now++;
            f.auction.tick();
            assertEquals(literal, f.info(task).snapshot);
            assertEquals(literal, f.loop.snapshot);
            assertEquals(literal.summary(), f.loop.message);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void survivalPauseAndIdleUiLocalizeWithoutChangingResumeOrDeadline(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        try (var f = new Fixture()) {
            var task = f.task("pause:{0}:ç");
            task.record().extendDeadlineTo(200);
            f.auction.submitTask(task);
            f.chain.priority = 10;
            f.auction.now = 101;
            String printed = capture(false, () -> assertSame(f.chain, f.auction.tick()));
            String reason = (pt ? "Pausada para a cadeia de sobrevivência " : "Paused for survival chain ") + f.chain.name;
            assertEquals(reason, f.info(task).message);
            assertEquals(reason, f.loop.message);
            assertEquals(reason, f.auction.ui.getLast());
            assertEquals("[MineAgent] " + (pt ? "Tarefa corporal pause:{0}:ç pausada para a cadeia de sobrevivência " : "Body task pause:{0}:ç paused for survival chain ") + f.chain.name + System.lineSeparator(), printed);
            assertEquals(TaskState.PAUSED, f.info(task).state);
            assertEquals(task.snapshot(), f.info(task).snapshot);
            assertEquals(1, task.interrupts);
            assertEquals(0, task.ticks);
            f.auction.now = 102;
            f.auction.tick();
            assertEquals(1, task.interrupts);
            assertEquals(2, f.chain.ticks);
            f.chain.priority = Float.NEGATIVE_INFINITY;
            f.auction.now = 150;
            assertNull(f.auction.tick());
            assertEquals(1, task.resumes);
            assertEquals(1, task.starts);
            assertEquals(1, task.ticks);
            assertEquals(249, task.record().deadline());
            assertEquals(TaskState.RUNNING, f.info(task).state);
            assertEquals("Probe", f.auction.ui.getLast());
            assertEquals(1, f.chain.interrupts);
            assertTrue(f.auction.cancelTask("pause:{0}:ç"));
            assertEquals(pt ? "Ocioso" : "Idle", f.auction.ui.getLast());
            assertEquals(task.terminal, f.info(task).message);
            assertEquals(2, task.interrupts);
            assertFalse(f.auction.hasRunningTask());
            assertFalse(f.auction.cancelTask("pause:{0}:ç"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void nullableExternalNamesAndIdsRemainLiteralInsteadOfBreakingScheduling(String language) {
        RuntimeLanguage.configure(language);
        boolean pt = language.equals("pt_br");
        try (var f = new Fixture()) {
            var task = f.task(null);
            f.auction.submitTask(task);
            var rejected = f.task("valid-rejection");
            assertFalse(f.auction.submitTask(rejected).accepted());
            assertTrue(f.info(rejected).message.contains("'null'"));
            f.chain.name = null;
            f.chain.priority = 10;
            String printed = capture(false, f.auction::tick);
            assertEquals((pt ? "Pausada para a cadeia de sobrevivência " : "Paused for survival chain ") + "null", f.loop.message);
            assertTrue(printed.contains(pt ? "Tarefa corporal null" : "Body task null"));
            assertTrue(printed.contains("null" + System.lineSeparator()));
            assertEquals(1, task.interrupts);
            assertTrue(f.auction.hasRunningTask());
            f.auction.cancelTask();
            assertFalse(f.auction.hasRunningTask());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void progressHeartbeatAndTaskBudgetKeepTheirExistingTickSemantics(String language) {
        RuntimeLanguage.configure(language);
        try (var f = new Fixture()) {
            var task = f.task("budget:{0}");
            task.blocker = "opaque blocker";
            task.record().extendDeadlineTo(125);
            f.auction.submitTask(task);
            assertEquals(1, f.loop.progress);
            for (int tick = 101; tick < 120; tick++) { f.auction.now = tick; f.auction.tick(); }
            assertEquals(19, task.ticks);
            assertEquals(1, f.loop.progress);
            f.auction.now = 120; f.auction.tick();
            assertEquals(2, f.loop.progress);
            task.version++;
            f.auction.now = 121; f.auction.tick();
            assertEquals(3, f.loop.progress);
            task.terminal = "timed out: literal {0} ç";
            f.auction.now = 125; f.auction.tick();
            assertEquals(21, task.ticks);
            assertEquals(TaskState.FAILED, f.info(task).state);
            assertEquals("timed out: literal {0} ç", f.info(task).message);
            assertEquals(25, f.info(task).elapsedTicks);
            assertEquals(1, f.loop.finished);
            assertEquals(1, task.interrupts);
            assertFalse(f.auction.hasRunningTask());
        }
    }

    @Test void languageReloadChangesNewTrustedProseButNeverHistoryOrReadyStrings() {
        RuntimeLanguage.configure("en_us");
        try (var f = new Fixture()) {
            var task = f.task("reload:{0}");
            task.summary = "Running";
            f.auction.submitTask(task);
            var raw = f.info(task);
            RuntimeLanguage.configure("pt_br");
            assertSame(raw, f.info(task));
            assertEquals("Running", f.info(task).message);
            task.nullSnapshot = true;
            f.auction.now++; f.auction.tick();
            var portuguese = f.info(task);
            assertEquals("Probe está em execução", portuguese.message);
            RuntimeLanguage.configure("en_us");
            assertSame(portuguese, f.info(task));
            f.auction.now += 20; f.auction.tick();
            assertEquals("Probe is running", f.info(task).message);
            assertEquals("Probe está em execução", portuguese.message);
            RuntimeLanguage.configure("pt_br");
            task.terminal = "Task failed with external English history:{0}:ç";
            assertTrue(f.auction.cancelTask("reload:{0}"));
            assertEquals(task.terminal, f.info(task).message);
            assertEquals(task.terminal, f.loop.message);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "pt_br"})
    void resumeAndPauseCleanupFailuresKeepExactTerminalOwnership(String language) {
        RuntimeLanguage.configure(language);
        String prefix = language.equals("pt_br") ? "A tarefa falhou com " : "Task failed with ";
        try (var f = new Fixture()) {
            var task = f.task("resume-failure:{0}");
            f.auction.submitTask(task);
            f.chain.priority = 10;
            capture(false, f.auction::tick);
            f.chain.priority = Float.NEGATIVE_INFINITY;
            task.resumeError = new IllegalStateException("external resume:{1}:ç");
            f.auction.now++;
            f.auction.tick();
            assertEquals(prefix + "IllegalStateException: external resume:{1}:ç", f.info(task).message);
            assertEquals(TaskState.FAILED, f.info(task).state);
            assertEquals(1, f.loop.finished);
            assertEquals(1, task.resumes);
            assertEquals(0, task.ticks);
            assertEquals(2, task.interrupts);
        }
        try (var f = new Fixture()) {
            var task = f.task("pause-failure:{0}");
            f.auction.submitTask(task);
            task.interruptError = new IllegalStateException("external cleanup:{1}:ç");
            f.chain.priority = 10;
            capture(true, f.auction::tick);
            assertEquals(prefix + "IllegalStateException: external cleanup:{1}:ç", f.info(task).message);
            assertEquals(TaskState.FAILED, f.info(task).state);
            assertEquals(1, f.loop.finished);
            assertEquals(2, task.interrupts);
            assertEquals(0, task.ticks);
            assertFalse(f.auction.hasRunningTask());
            assertSame(f.chain, f.auction.currentWinner());
        }
    }

    @Test void strictCatalogRejectsUnknownKeysAndWrongArityWithoutReinterpretingArguments() {
        for (String language : List.of("en_us", "pt_br")) {
            RuntimeLanguage.configure(language);
            assertThrows(IllegalArgumentException.class, () -> SchedulerMessages.text("does.not.exist"));
            assertThrows(IllegalArgumentException.class, () -> SchedulerMessages.text("task.running", "extra"));
            assertThrows(IllegalArgumentException.class, () -> SchedulerMessages.text("task.error", "missing"));
            assertThrows(NullPointerException.class, () -> SchedulerMessages.text("snapshot.error", (Object) null));
            assertTrue(SchedulerMessages.text("snapshot.error", "raw:{0} $1 \\n ç").endsWith("raw:{0} $1 \\n ç"));
            for (var state : TaskState.values()) {
                String expected = language.equals("en_us") ? state.name().toLowerCase(java.util.Locale.ROOT)
                        : switch (state) {
                            case PENDING -> "pendente";
                            case RUNNING -> "em execução";
                            case PAUSED -> "pausada";
                            case SUCCESS -> "sucesso";
                            case FAILED -> "falhou";
                            case CANCELLED -> "cancelada";
                        };
                assertEquals(expected, SchedulerMessages.text("fallback." + state.name()));
            }
        }
    }

    static String capture(boolean stderr, Runnable action) {
        var old = stderr ? System.err : System.out;
        var bytes = new java.io.ByteArrayOutputStream();
        try (var stream = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            if (stderr) System.setErr(stream); else System.setOut(stream);
            action.run();
        } finally {
            if (stderr) System.setErr(old); else System.setOut(old);
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    static final class Fixture implements AutoCloseable {
        final CompanionEntity body = new CompanionEntity(null, null, "fixture");
        final RecordingLoop loop = new RecordingLoop();
        final ProbeChain chain = new ProbeChain();
        final ClockAuction auction = new ClockAuction(body, loop, List.of(chain));
        final List<ProbeTask> tasks = new ArrayList<>();
        ProbeTask task(String id) {
            var task = new ProbeTask(body, new ProbeRecord(id)); tasks.add(task); return task;
        }
        TaskStatusTool.TaskInfo info(ProbeTask task) { return TaskStatusTool.getTaskInfo(body.companionId(), task.record().toolCallId()); }
        @Override public void close() {
            tasks.forEach(t -> TaskStatusTool.removeTaskInfo(body.companionId(), t.record().toolCallId()));
            TaskContext.removeCaches(body);
        }
    }
    static final class ClockAuction extends PriorityAuction {
        long now = 100;
        final List<String> ui = new ArrayList<>();
        ClockAuction(AgentPlayer body, AgentLoop loop, List<TaskChain> chains) { super(body, chains, loop); }
        @Override protected long gameTime() { return now; }
        @Override protected void pushTaskUpdate(String description) { ui.add(description); }
    }
    static final class RecordingLoop extends AgentLoop {
        int accepted, progress, finished;
        IntentContract intent;
        String message, taskId;
        TaskState state;
        TaskSnapshot snapshot;
        final List<String> logs = new ArrayList<>();
        RecordingLoop() { super(null, "ollama", "", "fixture", "http://127.0.0.1", 0, 100, "auto"); }
        @Override public void onTaskAccepted(String id, String name, IntentContract contract, TaskSnapshot snapshot, long tick) {
            accepted++; intent = contract; this.snapshot = snapshot;
        }
        @Override public void onTaskProgress(String id, TaskState state, TaskSnapshot snapshot, String message, long tick) {
            progress++; this.taskId = id; this.state = state; this.snapshot = snapshot; this.message = message;
        }
        @Override public void onTaskFinished(String id, String name, IntentContract contract, TaskState state, TaskSnapshot snapshot, String message, long tick) {
            finished++; this.taskId = id; intent = contract; this.state = state; this.snapshot = snapshot; this.message = message;
        }
        @Override public void onBodyLog(String message) { logs.add(message); }
    }
    static final class ProbeRecord extends TaskRecord { ProbeRecord(String id) { super(id); } }
    static final class ProbeTask extends CompanionTask<ProbeRecord> {
        int starts, ticks, interrupts, resumes;
        TaskState nextState = TaskState.RUNNING;
        RuntimeException startError, tickError, interruptError, resumeError, snapshotError;
        boolean nullSnapshot, completionError;
        String terminal = "external done:{0}:ç", summary = "external summary:{0}:ç", blocker;
        long version;
        Runnable atStart = () -> {};
        ProbeTask(AgentPlayer body, ProbeRecord record) { super(body, record); }
        @Override protected void onStart() { starts++; atStart.run(); if (startError != null) throw startError; }
        @Override protected void onResume() { resumes++; if (resumeError != null) throw resumeError; }
        @Override protected TaskState onTick() { ticks++; if (tickError != null) throw tickError; return nextState; }
        @Override protected void onInterrupt() { interrupts++; if (interruptError != null) throw interruptError; }
        @Override public TaskSnapshot snapshot() {
            if (snapshotError != null) throw snapshotError;
            return nullSnapshot ? null : TaskSnapshot.progress("opaque_stage", summary, 2, 7, 4, -5, 6, blocker, "opaque evidence:{0}:ç", version);
        }
        @Override protected String successMessage() { if (completionError) throw new IllegalStateException("external completion error"); return terminal; }
        @Override protected String failureMessage() { if (completionError) throw new IllegalStateException("external completion error"); return terminal; }
        @Override protected String timeoutMessage() { if (completionError) throw new IllegalStateException("external completion error"); return terminal; }
        @Override protected String cancelledMessage() { if (completionError) throw new IllegalStateException("external completion error"); return terminal; }
    }
    static final class ProbeChain implements TaskChain {
        float priority = Float.NEGATIVE_INFINITY;
        int ticks, interrupts;
        String name = "Danger:{0}:ç";
        RuntimeException priorityError, tickError, interruptError;
        @Override public float getPriority(AgentPlayer body) { if (priorityError != null) throw priorityError; return priority; }
        @Override public void tick(AgentPlayer body) { ticks++; if (tickError != null) throw tickError; }
        @Override public void onInterrupt(AgentPlayer body) { interrupts++; if (interruptError != null) throw interruptError; }
        @Override public String name() { return name; }
    }
}
