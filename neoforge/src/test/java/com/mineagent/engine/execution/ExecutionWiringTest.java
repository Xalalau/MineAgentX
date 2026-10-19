package com.mineagent.engine.execution;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Supplementary wiring guards. Behavioral coverage lives in the production-helper tests. */
final class ExecutionWiringTest {
    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("mineagentx.projectRoot", ".")).toAbsolutePath();
        if (!Files.exists(root.resolve("engine"))) root = root.getParent();
        return Files.readString(root.resolve(relative));
    }
    private static String loop() throws Exception {
        return source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
    }

    @Test void bothEntryPathsUseOnePhysicalDispatcherAndOneCallbackPolicy() throws Exception {
        String source = loop();
        String skill = source.substring(source.indexOf("private SkillRuntime.DispatchResult dispatchSkillAction("),
                source.indexOf("private void cancelSkillAction("));
        String nativeCalls = source.substring(source.indexOf("private List<ChatMessage> executeToolCalls("),
                source.indexOf("private void cancelOwnedTaskNow("));
        for (String path : new String[]{skill, nativeCalls}) {
            assertTrue(path.contains("prepareExecution("));
            assertTrue(path.contains("dispatchExecution("));
            assertFalse(path.contains(".onServerCall("));
        }
        assertEquals(1, source.split("\\.onServerCall\\(", -1).length - 1);
        assertTrue(skill.contains(".requireImmediate("));
        assertTrue(source.contains("execution.call().dispatch("));
    }

    @Test void cancellationAndNewOwnerInputRetainExactServerOwnedCleanup() throws Exception {
        String source = loop();
        assertEquals(1, source.split("auction\\.cancelTask\\(", -1).length - 1);
        assertTrue(source.contains("state.auction.cancelTask(taskId)"));
        assertTrue(source.contains("scheduleOnServer(() -> cancelOwnedTaskNow(actionId))"));
        for (String method : new String[]{"public synchronized void cancel()", "public void onOwnerMessage(String message)"}) {
            int start = source.indexOf(method);
            String body = source.substring(start, source.indexOf("\n    }", start));
            assertTrue(body.contains("executionOwner.revokeAll(executionUnavailable(), eventGeneration::incrementAndGet)"));
        }
        assertTrue(source.contains("executionOwner.release(taskId)"));
        assertTrue(source.contains("Objects.equals(skillRuntime.snapshot().runId(), runId)"));
    }

    @Test void lateReceiptsCannotInvokeLearnedOutcomeWriters() throws Exception {
        String source = loop();
        String late = source.substring(source.indexOf("private void reconcileLateToolReceipt("),
                source.indexOf("private void markExecutionUnverified("));
        assertTrue(late.contains("publishWorldSnapshotNow(true)"));
        assertTrue(late.contains("late_tool_receipt"));
        assertFalse(late.contains("identity.recordEvidence"));
        assertFalse(late.contains("recordOutcome("));
        assertFalse(late.contains("finishObserved("));
        assertFalse(late.contains("onToolResult("));
    }

    @Test void actualCraftLoopChecksTheScopedTokenBeforeEachBoundedAtomicBatch() throws Exception {
        String craft = source("tools/src/main/java/com/mineagent/tools/crafting/CraftTool.java");
        int batch = craft.indexOf("for (int batch = 0; batch < batchesToCraft; batch++)");
        int token = craft.indexOf("ExecutionToken.active()", batch);
        assertTrue(batch >= 0 && token > batch && token < craft.indexOf("createPlan(", batch));
        assertTrue(token < craft.indexOf("InventoryCraftTransactions.craftAndNotify", batch));
        assertTrue(craft.contains("result.addProperty(\"crafted_batches\", craftedBatches)"));
        assertTrue(craft.contains("result.addProperty(\"complete\", craftedBatches == batchesToCraft && allDeliveriesVerified)"));
    }
}
