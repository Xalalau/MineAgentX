package com.mineagent.engine.conversation;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.execution.ExecutionToken;
import com.mineagent.api.task.CompanionTickDispatcher;
import com.mineagent.engine.execution.ExecutionOwner;
import com.mineagent.engine.execution.ExecutionProtocol;
import com.mineagent.tools.inventory.CollectItemsTool;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static com.mineagent.engine.conversation.ConversationRuntimeFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual admitted AgentLoop tool body and queued record, not a token-only fixture. */
final class CollectionRetainedAuthorityTest {
    private static Object prepare(ConversationRuntimeFixture f, String id) throws Exception {
        return invoke(f.loop, "prepareExecution", new Class<?>[]{String.class, Tool.class, long.class, BooleanSupplier.class},
                id, new CollectItemsTool(), ((AtomicLong) field(f.loop, "eventGeneration")).get(), (BooleanSupplier) () -> true);
    }
    private static CollectItemsTool.CollectItemsTaskRecord dispatch(ConversationRuntimeFixture f, Object execution) throws Exception {
        var args = new JsonObject(); args.addProperty("item_id", "minecraft:stone"); args.addProperty("count", 5);
        invoke(f.loop, "dispatchExecution", new Class<?>[]{execution.getClass(), Tool.class, JsonObject.class},
                execution, new CollectItemsTool(), args);
        var queued = CompanionTickDispatcher.drain();
        assertEquals(1, queued.size()); assertSame(f.body, queued.getFirst().player());
        return (CollectItemsTool.CollectItemsTaskRecord) queued.getFirst().record();
    }
    private static boolean retained(CollectItemsTool.CollectItemsTaskRecord record) throws Exception {
        // Reflective boundary lets the immutable pre-feature artifact reach a behavioral RED.
        try { return (boolean) record.getClass().getMethod("retainedAuthorityActive").invoke(record); }
        catch (NoSuchMethodException absent) { return false; }
    }
    @Test void realQueuedRecordRetainsExactAdmittedLeaseBeyondAckAndRejectsReusedId() throws Exception {
        try (var f = new ConversationRuntimeFixture()) {
            assertTrue(CompanionTickDispatcher.drain().isEmpty());
            var execution = prepare(f, "same-id");
            var record = dispatch(f, execution);
            var lease = (ExecutionOwner.Lease) invoke(execution, "lease", new Class<?>[0]);
            assertEquals(ExecutionProtocol.State.COMPLETED, lease.call().terminal().state());
            assertFalse(lease.call().active()); assertTrue(lease.active());
            assertTrue(record.requiresRetainedAuthority());
            assertTrue(retained(record), "RETAINED_AUTHORITY: real queued record must retain the admitted lease after async ack");
            f.loop.onOwnerMessage("ordinary speech preserves admitted collection");
            assertTrue(retained(record));
            assertTrue(((ExecutionOwner) field(f.loop, "executionOwner")).revoke("same-id", "fixture explicit revocation"));
            assertFalse(retained(record));
            assertTrue(record.requiresRetainedAuthority(), "Revoked work must never fall back to unbound legacy behavior");
            var replacement = dispatch(f, prepare(f, "same-id"));
            assertTrue(retained(replacement)); assertFalse(retained(record), "ID reuse cannot revive the old record");
            var outside = new CollectItemsTool.CollectItemsTaskRecord("same-id", "minecraft:stone", 8, 5);
            assertFalse(retained(outside), "Unbound legacy invocation must not acquire cap authority");
            assertFalse(outside.requiresRetainedAuthority());
            f.loop.pause(); assertFalse(retained(replacement));
        } finally { CompanionTickDispatcher.drain(); }
    }
    @Test void rejectedAdmissionNeverInvokesToolOrQueuesRecord() throws Exception {
        try (var f = new ConversationRuntimeFixture()) {
            var execution = prepare(f, "stale");
            f.loop.onOwnerMessage("invalidate pending reasoning only");
            invoke(f.loop, "dispatchExecution", new Class<?>[]{execution.getClass(), Tool.class, JsonObject.class},
                    execution, new CollectItemsTool(), new JsonObject());
            assertTrue(CompanionTickDispatcher.drain().isEmpty());
            assertTrue(ExecutionToken.active(), "Legacy invocation default remains unchanged");
        }
    }
}
