package com.mineagent.inventory;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Supplementary source wiring only; loaded physical witnesses live in the isolated test mod. */
class CollectionCollisionWiringTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of(System.getProperty("mineagentx.projectRoot", ".."), relative));
    }

    @Test void engineClosesObservationAroundPhysicalTickEvenOnFailure() throws Exception {
        String engine = source("engine/src/main/java/com/mineagent/engine/MineAgentEngine.java");
        int auction = engine.indexOf("state.auction.tick();", engine.indexOf("public static void onServerTick"));
        int scope = engine.indexOf("try (var collisionFrame =", engine.indexOf("public static void onServerTick"));
        int physics = engine.indexOf("sp.connection.tick()", scope);
        int catchFailure = engine.indexOf("} catch (Throwable t)", physics);
        assertTrue(scope > 0 && auction > scope && physics > auction && catchFailure > physics);
        String observation = engine.substring(scope, physics);
        assertTrue(observation.contains("state.lifecycle.isPaused() || state.lifecycle.isDead()"));
        assertTrue(observation.contains("state.auction.physicalFrameTask()"));
    }

    @Test void schedulerExposesOnlyItsUnpausedPhysicalTask() throws Exception {
        String auction = source("engine/src/main/java/com/mineagent/engine/scheduler/PriorityAuction.java");
        int accessor = auction.indexOf("public CompanionTask<?> physicalTask()");
        assertTrue(accessor > 0);
        assertTrue(auction.substring(accessor, auction.indexOf("\n    }", accessor))
                .contains("return taskPaused ? null : currentTask;"));
    }

    @Test void frameRequiresAnAttemptAndOnlyVetoesWithinRetainedBudget() throws Exception {
        String frame = source("engine/src/main/java/com/mineagent/engine/task/CollectionCollisionFrame.java");
        assertTrue(frame.contains("new CollectionPickupObservation(event.getItemEntity(), player.getInventory())"));
        assertTrue(frame.contains("attempts.remove(event.getItemEntity())"));
        assertTrue(frame.contains("task.capsCollisions()") && frame.contains("task.reserveCollisionPickup(reserved)"));
        assertTrue(frame.contains("event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE)"));
        assertTrue(frame.contains("event.getPlayer() != player"));
        assertTrue(frame.contains("task.settleCollisionPickup(attempt.reserved(), attempt.observation().delivered())"));
        assertTrue(frame.contains("unregister(pre)") && frame.contains("unregister(post)"));
        assertTrue(frame.contains("CURRENT.remove()") && frame.contains("attempts.clear()"));
        for (String mutation : new String[] {"TriState.TRUE", "setCount(", "split(", "setItem(", "playerTouch(", "ExecutionToken.active()"}) {
            assertFalse(frame.contains(mutation), "Scoped guard must not grant pickup, mutate inventory or infer authority: " + mutation);
        }
    }
}
