package com.mineagent.engine.conversation;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.execution.ExecutionToken;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.execution.*;
import com.mineagent.engine.skill.SkillRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static com.mineagent.engine.conversation.ConversationRuntimeFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class OwnerSpeechRuntimeTest {
    @Test void actualNativeDispatcherPublishesCallbackAfterSpeechWithoutLosingItsToken() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            AtomicBoolean stillAuthorized = new AtomicBoolean();
            Tool tool = new Tool() {
                public String name() { return "fixture_action"; }
                public String description() { return "fixture"; }
                public Map<String,Object> parameterSchema() { return Map.of(); }
                public void onServerCall(String id, JsonObject args, AgentPlayer body, Consumer<String> reply) {
                    fixture.loop.onOwnerMessage("ordinary conversation during a bounded action");
                    stillAuthorized.set(ExecutionToken.active());
                    reply.accept("{\"success\":true,\"receipt\":\"actual fixture callback\"}");
                }
            };
            long generation = ((AtomicLong) field(fixture.loop, "eventGeneration")).get();
            Object execution = invoke(fixture.loop, "prepareExecution", new Class<?>[]{String.class, Tool.class, long.class, java.util.function.BooleanSupplier.class},
                    "native", tool, generation, (java.util.function.BooleanSupplier) () -> true);
            invoke(fixture.loop, "dispatchExecution", new Class<?>[]{execution.getClass(), Tool.class, JsonObject.class}, execution, tool, new JsonObject());
            ExecutionOwner.Lease lease = (ExecutionOwner.Lease) invoke(execution, "lease", new Class<?>[0]);
            assertTrue(stillAuthorized.get());
            assertEquals(ExecutionProtocol.State.COMPLETED, lease.call().terminal().state());
            assertTrue(lease.call().terminal().payload().contains("actual fixture callback"));
            assertTrue(fixture.loop.cognitiveState().eventsSince(0).stream().noneMatch(e -> e.type().equals("late_tool_receipt")));
            assertTrue(fixture.server.isEmpty(), "ordinary speech must not enqueue physical cancellation");
        }
    }

    @Test void admittedSkillAndItsRealAsyncTerminalSurviveUninterpretedSpeech() throws Exception {
        List<Tool> registered = new ArrayList<>(ToolRegistry.all());
        try (var fixture = new ConversationRuntimeFixture()) {
            var action = new AtomicReference<String>();
            ToolRegistry.clear();
            ToolRegistry.register(new Tool() {
                public String name() { return "craft"; }
                public String description() { return "fixture"; }
                public Map<String,Object> parameterSchema() { return Map.of(); }
                public boolean dispatchesAsyncTask() { return true; }
                public void onServerCall(String id, JsonObject args, AgentPlayer body, Consumer<String> reply) {
                    action.set(id);
                    fixture.loop.onOwnerMessage("a question, not a new task");
                    assertTrue(ExecutionToken.active());
                    reply.accept("{\"async\":true}");
                }
            });
            fixture.loop.skillLibrary().registerSequence("authority", "verified execution", "execution", "[{\"tool\":\"craft\",\"args\":{}}]", true);
            assertTrue(fixture.loop.startSkill("authority", null).accepted());
            fixture.loop.skillRuntime().tick(3);
            assertEquals(SkillRuntime.Status.WAITING_TASK, fixture.loop.skillRuntime().snapshot().status());
            fixture.loop.onOwnerMessage("another conversational utterance");
            assertTrue(fixture.loop.skillRuntime().active());
            fixture.loop.onTaskFinished(action.get(), "craft", null, TaskState.SUCCESS, null, "fixture physical receipt", 4);
            fixture.loop.skillRuntime().tick(5);
            assertEquals(SkillRuntime.Status.SUCCEEDED, fixture.loop.skillRuntime().snapshot().status());
            assertEquals(2, fixture.loop.skillLibrary().get("authority").orElseThrow().invocations());
        } finally { ToolRegistry.clear(); registered.forEach(ToolRegistry::register); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicitCancelAndLifecyclePauseStillRevokeAdmittedAndQueuedWork(boolean pause) throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            var admitted = OwnerSpeechAuthorityTest.prepare(fixture, "running");
            var queued = OwnerSpeechAuthorityTest.prepare(fixture, "queued");
            assertTrue(admitted.call().admit());
            fixture.loop.onOwnerMessage("conversation preserves the task");
            assertTrue(admitted.active());
            if (pause) fixture.loop.pause(); else fixture.loop.cancel();
            assertFalse(admitted.active());
            assertFalse(admitted.call().complete("late receipt", () -> fail("revoked callbacks cannot publish authority")));
            assertFalse(queued.call().admit());
            assertEquals(ExecutionProtocol.State.EXPIRED, admitted.call().terminal().state());
            assertFalse(fixture.server.isEmpty(), "real revocation queues exact-task cleanup on the server");
        }
    }
}
