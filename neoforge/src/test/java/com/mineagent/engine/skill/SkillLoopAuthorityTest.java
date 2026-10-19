package com.mineagent.engine.skill;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.platform.Services;
import com.mineagent.engine.execution.ExecutionOwner;
import com.mineagent.engine.identity.EvolvingIdentity;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.engine.world.ActionTransactionLedger;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loop dispatcher and outcome callbacks; no server body or HTTP model is booted. */
final class SkillLoopAuthorityTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.mineagent.api.task.TaskState.class, names = {"SUCCESS", "FAILED"})
    void authoritativeSchedulerTerminalStillTrainsVerifiedPhysicalOutcome(com.mineagent.api.task.TaskState state) throws Exception {
        try (var fixture = new Fixture()) {
            var id = new AtomicReference<String>();
            fixture.tool((action, reply) -> { id.set(action); reply.accept("{\"async\":true}"); }, true);
            fixture.runtime.start("authority", null, 1); fixture.runtime.tick(2);
            fixture.loop.onTaskFinished(id.get(), "craft", null, state, null, "physical outcome", 3);
            fixture.runtime.tick(4);
            var skill = fixture.library.get("authority").orElseThrow();
            assertEquals(2, skill.invocations());
            assertEquals(state == com.mineagent.api.task.TaskState.SUCCESS ? 1.0 : 0.5, skill.successRate());
            assertEquals(SkillRuntime.Disposition.VERIFIED, fixture.runtime.snapshot().disposition());
            assertEquals(state == com.mineagent.api.task.TaskState.SUCCESS ? ActionTransactionLedger.Status.VERIFIED
                    : ActionTransactionLedger.Status.FAILED, fixture.ledger.get(id.get()).status());
            assertEquals(1, fixture.world.exportState().events().stream()
                    .filter(e -> e.subject().equals("tool:craft") && e.type() == SemanticWorldModel.EventType.OUTCOME).count());
        }
    }

    @Test void synchronousSchedulerCompletionCannotBeOverwrittenByAdmissionReply() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.tool((action, reply) -> {
                fixture.loop.onTaskFinished(action, "craft", null, com.mineagent.api.task.TaskState.SUCCESS,
                        null, "physical completion before admission reply", 2);
                reply.accept("{\"async\":true}");
            }, true);
            fixture.runtime.start("authority", null, 1); fixture.runtime.tick(2); fixture.runtime.tick(3);
            assertEquals(2, fixture.library.get("authority").orElseThrow().invocations());
            assertEquals(1.0, fixture.library.get("authority").orElseThrow().successRate());
            assertEquals(SkillRuntime.Status.SUCCEEDED, fixture.runtime.snapshot().status());
            assertEquals(1, fixture.runtime.snapshot().stepIndex());
            assertEquals(1, fixture.world.exportState().events().stream()
                    .filter(e -> e.subject().equals("tool:craft") && e.type() == SemanticWorldModel.EventType.OUTCOME).count());
        }
    }

    @Test void schedulerCancellationDoesNotTeachAPhysicalFailure() throws Exception {
        try (var fixture = new Fixture()) {
            var id = new AtomicReference<String>();
            fixture.tool((action, reply) -> { id.set(action); reply.accept("{\"async\":true}"); }, true);
            fixture.runtime.start("authority", null, 1); fixture.runtime.tick(2);
            var before = fixture.library.get("authority").orElseThrow();
            fixture.loop.onTaskFinished(id.get(), "craft", null, com.mineagent.api.task.TaskState.CANCELLED,
                    null, "cancelled after one physical batch", 4);
            assertEquals(before, fixture.library.get("authority").orElseThrow());
            assertEquals(SkillRuntime.Disposition.CANCELLED, fixture.runtime.snapshot().disposition());
            assertEquals(ActionTransactionLedger.Status.CANCELLED, fixture.ledger.get(id.get()).status());
            assertTrue(fixture.world.exportState().events().stream()
                    .noneMatch(e -> e.subject().equals("tool:craft") && e.type() == SemanticWorldModel.EventType.OUTCOME));
        }
    }

    @Test void cancelledSkillCannotLearnFromItsLaterSchedulerSuccess() throws Exception {
        try (var fixture = new Fixture()) {
            var id = new AtomicReference<String>();
            fixture.tool((action, reply) -> { id.set(action); reply.accept("{\"async\":true}"); }, true);
            fixture.runtime.start("authority", null, 1);
            fixture.runtime.tick(2);
            assertEquals(SkillRuntime.Status.WAITING_TASK, fixture.runtime.snapshot().status());
            fixture.runtime.cancel("owner revoked", 3);
            var before = fixture.library.get("authority").orElseThrow();
            var identityBefore = fixture.identity.exportState();
            fixture.loop.onTaskFinished(id.get(), "craft", null, com.mineagent.api.task.TaskState.SUCCESS,
                    null, "{\"success\":true,\"crafted_batches\":1}", 4);
            assertEquals(before, fixture.library.get("authority").orElseThrow());
            assertEquals(identityBefore, fixture.identity.exportState());
            assertTrue(fixture.world.exportState().events().stream()
                    .noneMatch(e -> e.subject().equals("tool:craft") && e.type() == SemanticWorldModel.EventType.OUTCOME));
            assertEquals(SkillRuntime.Status.CANCELLED, fixture.runtime.snapshot().status());
            assertEquals(ActionTransactionLedger.Status.UNVERIFIED, fixture.ledger.get(id.get()).status());
        }
    }

    @Test void completedUncertaintyPayloadCannotTrainToolOrSkill() throws Exception {
        try (var fixture = new Fixture()) {
            var id = new AtomicReference<String>();
            String receipt = "{\"error\":\"interrupted after partial work\",\"code\":\"tool_cancelled\",\"crafted_batches\":1}";
            fixture.tool((action, reply) -> { id.set(action); reply.accept(receipt); });
            var before = fixture.library.get("authority").orElseThrow();
            fixture.runtime.start("authority", null, 1);
            fixture.runtime.tick(2);
            assertEquals(before, fixture.library.get("authority").orElseThrow());
            assertEquals(SkillRuntime.Disposition.CANCELLED, fixture.runtime.snapshot().disposition());
            assertEquals(receipt, fixture.runtime.snapshot().lastEvidence());
            assertEquals(ActionTransactionLedger.Status.UNVERIFIED, fixture.ledger.get(id.get()).status());
            assertTrue(fixture.world.exportState().events().stream()
                    .noneMatch(e -> e.subject().equals("tool:craft") && e.type() == SemanticWorldModel.EventType.OUTCOME));
            assertTrue(fixture.identity.evidence().stream().noneMatch(e -> e.source().equals("tool:craft")));
        }
    }

    @Test void rejectedAsyncAdmissionDoesNotRetainWaitingAuthority() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.tool((action, reply) -> reply.accept("{\"error\":\"invalid arguments\"}"), true);
            fixture.runtime.start("authority", null, 1);
            fixture.runtime.tick(2);
            assertEquals(SkillRuntime.Status.NEEDS_REPLAN, fixture.runtime.snapshot().status());
            assertTrue(((Map<?, ?>) field(fixture.loop, "skillTaskExecutions")).isEmpty(),
                    "A definitive rejected admission has no scheduler terminal callback to release its entry");
        }
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static final class Fixture implements AutoCloseable {
        final List<Tool> tools = new ArrayList<>(ToolRegistry.all());
        final java.lang.reflect.Field platformField;
        final Object oldPlatform;
        final ArrayDeque<Runnable> server = new ArrayDeque<>();
        final AgentLoop loop;
        final SkillRuntime runtime;
        final SkillLibrary library;
        final SemanticWorldModel world;
        final EvolvingIdentity identity;
        final ActionTransactionLedger ledger;
        Fixture() throws Exception {
            platformField = Services.class.getDeclaredField("PLATFORM"); platformField.setAccessible(true);
            oldPlatform = platformField.get(null);
            platformField.set(null, new Services.IPlatform() {
                public void scheduleOnServer(Runnable task) { server.add(task); }
                public Optional<UUID> findPlayerUuid(String name) { return Optional.empty(); }
                public boolean isPlayerOnline(UUID id) { return false; }
                public boolean isClientSide() { return false; }
                public long gameTime() { return 2; }
            });
            loop = new AgentLoop(new TestBody(), "ollama", "", "test", "http://127.0.0.1", 0, 100, "auto");
            set(loop, "inProgress", true); // Retain events without starting model work in this fixture.
            runtime = (SkillRuntime) field(loop, "skillRuntime");
            library = (SkillLibrary) field(loop, "skillLib");
            world = (SemanticWorldModel) field(loop, "semanticWorldModel");
            identity = (EvolvingIdentity) field(loop, "identity");
            ledger = (ActionTransactionLedger) field(loop, "actionLedger");
            library.registerSequence("authority", "verified execution", "execution", "[{\"tool\":\"craft\",\"args\":{}}]", true);
            var owner = (ExecutionOwner) field(loop, "executionOwner");
            set(loop, "activeSkillLease", owner.open("fixture-run", () -> true, Long.MAX_VALUE, null));
        }
        void tool(java.util.function.BiConsumer<String, Consumer<String>> behavior) { tool(behavior, false); }
        void tool(java.util.function.BiConsumer<String, Consumer<String>> behavior, boolean async) {
            ToolRegistry.clear();
            ToolRegistry.register(new Tool() {
                public boolean dispatchesAsyncTask() { return async; }
                public String name() { return "craft"; }
                public String description() { return "fixture"; }
                public Map<String, Object> parameterSchema() { return Map.of(); }
                public void onServerCall(String id, JsonObject args, AgentPlayer body, Consumer<String> reply) { behavior.accept(id, reply); }
            });
        }
        public void close() throws Exception {
            ToolRegistry.clear(); tools.forEach(ToolRegistry::register);
            platformField.set(null, oldPlatform);
        }
    }
    private static final class TestBody extends AgentPlayer {
        public UUID companionId() { return new UUID(0, 1); }
        public UUID ownerUuid() { return new UUID(0, 2); }
        public String companionName() { return "test companion"; }
        public String ownerName() { return "test owner"; }
        public String blockPositionStr() { return "0,0,0"; }
        public double posX() { return 0; } public double posY() { return 0; } public double posZ() { return 0; }
        public float health() { return 20; } public float maxHealth() { return 20; }
        public int foodLevel() { return 20; } public int airSupply() { return 300; }
        public boolean isInWater() { return false; } public boolean isAlive() { return true; }
        public void holdInHand(int slot) { }
        public String mainHandItemId() { return "minecraft:air"; }
        public String dimensionKey() { return "minecraft:overworld"; }
    }
}
