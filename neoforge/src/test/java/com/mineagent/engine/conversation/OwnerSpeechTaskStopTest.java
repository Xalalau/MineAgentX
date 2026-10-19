package com.mineagent.engine.conversation;

import com.google.gson.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.*;
import com.mineagent.engine.scheduler.PriorityAuction;
import com.mineagent.tools.management.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Native task_stop and real scheduler terminals; only time/UI/world boundaries are fixtures. */
final class OwnerSpeechTaskStopTest {
    @Test void nativeStopIsDeliberateAndStillStopsTheExactPhysicalTaskAfterConversation() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            PriorityAuction auction = auction(fixture);
            fixture.installAuction(auction);
            BodyTask task = new BodyTask(fixture.body(), "body-task");
            try {
                assertTrue(auction.submitTask(task).accepted());
                fixture.loop.onOwnerMessage("ordinary speech while working");
                assertTrue(auction.hasRunningTask());
                assertEquals(TaskState.RUNNING, TaskStatusTool.getTaskInfo(fixture.body().companionId(), "body-task").state);
                JsonObject stopped = stop(fixture, "body-task");
                assertTrue(stopped.get("success").getAsBoolean());
                assertFalse(auction.hasRunningTask());
                assertEquals(1, task.interrupts);
                assertEquals(TaskState.CANCELLED, TaskStatusTool.getTaskInfo(fixture.body().companionId(), "body-task").state);
            } finally { TaskStatusTool.removeTaskInfo(fixture.body().companionId(), "body-task"); }
        }
    }

    @Test void deferredRevocationCannotCancelAReplacementWithADifferentPhysicalId() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            PriorityAuction auction = auction(fixture);
            fixture.installAuction(auction);
            var old = new BodyTask(fixture.body(), "old");
            var replacement = new BodyTask(fixture.body(), "replacement");
            try {
                assertTrue(preparePhysical(fixture, "old").call().admit());
                assertTrue(auction.submitTask(old).accepted());
                fixture.loop.cancel(); // queue cleanup for old only
                assertTrue(stop(fixture, "old").get("success").getAsBoolean());
                assertTrue(auction.submitTask(replacement).accepted());
                while (!fixture.server.isEmpty()) fixture.server.remove().run();
                assertTrue(auction.hasRunningTask());
                assertEquals(0, replacement.interrupts);
                assertEquals(TaskState.RUNNING, TaskStatusTool.getTaskInfo(fixture.body().companionId(), "replacement").state);
            } finally {
                auction.cancelTask("replacement");
                TaskStatusTool.removeTaskInfo(fixture.body().companionId(), "old");
                TaskStatusTool.removeTaskInfo(fixture.body().companionId(), "replacement");
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void explicitCancelOrPauseCleansItsActualTaskExactlyOnce(boolean pause) throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            PriorityAuction auction = auction(fixture);
            fixture.installAuction(auction);
            var task = new BodyTask(fixture.body(), "owned");
            try {
                assertTrue(preparePhysical(fixture, "owned").call().admit());
                assertTrue(auction.submitTask(task).accepted());
                if (pause) fixture.loop.pause(); else fixture.loop.cancel();
                while (!fixture.server.isEmpty()) fixture.server.remove().run();
                assertFalse(auction.hasRunningTask());
                assertEquals(1, task.interrupts);
                assertEquals(TaskState.CANCELLED, TaskStatusTool.getTaskInfo(fixture.body().companionId(), "owned").state);
            } finally { TaskStatusTool.removeTaskInfo(fixture.body().companionId(), "owned"); }
        }
    }

    private static JsonObject stop(ConversationRuntimeFixture fixture, String id) {
        JsonObject args = new JsonObject(); args.addProperty("task_id", id);
        AtomicReference<String> reply = new AtomicReference<>();
        new TaskStopTool().onServerCall("deliberate-native-stop", args, fixture.body(), reply::set);
        return JsonParser.parseString(reply.get()).getAsJsonObject();
    }
    private static com.mineagent.engine.execution.ExecutionOwner.Lease preparePhysical(ConversationRuntimeFixture fixture, String id) throws Exception {
        var tool = new com.mineagent.api.agent.tool.Tool() {
            public String name() { return "fixture_body"; }
            public String description() { return "fixture"; }
            public java.util.Map<String,Object> parameterSchema() { return java.util.Map.of(); }
            public boolean dispatchesAsyncTask() { return true; }
            public void onServerCall(String id, JsonObject args, AgentPlayer body, java.util.function.Consumer<String> reply) { reply.accept("{\"async\":true}"); }
        };
        long generation = ((java.util.concurrent.atomic.AtomicLong) ConversationRuntimeFixture.field(fixture.loop, "eventGeneration")).get();
        Object execution = ConversationRuntimeFixture.invoke(fixture.loop, "prepareExecution", new Class<?>[]{String.class, com.mineagent.api.agent.tool.Tool.class, long.class, java.util.function.BooleanSupplier.class},
                id, tool, generation, (java.util.function.BooleanSupplier) () -> true);
        return (com.mineagent.engine.execution.ExecutionOwner.Lease) ConversationRuntimeFixture.invoke(execution, "lease", new Class<?>[0]);
    }
    private static PriorityAuction auction(ConversationRuntimeFixture fixture) {
        return new PriorityAuction(fixture.body(), List.of(), fixture.loop) {
            protected long gameTime() { return 2; }
            protected void pushTaskUpdate(String text) { }
        };
    }
    private static final class BodyTask extends CompanionTask<TaskRecord> {
        int interrupts;
        BodyTask(AgentPlayer body, String id) { super(body, new TaskRecord(id) {}); }
        protected void onStart() { }
        protected TaskState onTick() { return TaskState.RUNNING; }
        protected void onInterrupt() { interrupts++; }
    }
}
