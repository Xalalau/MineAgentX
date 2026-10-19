package com.mineagent.safety;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring regressions complement the executable mutation policy test. */
class WorldMutationWiringTest {
    private static final Path ROOT = Path.of(System.getProperty("mineagentx.projectRoot", ".."));
    private static String source(String path) throws Exception { return Files.readString(ROOT.resolve(path)); }

    @Test void navigationNeverPlansOrExecutesUnrequestedMining() throws Exception {
        String text = source("engine/src/main/java/com/mineagent/engine/pathing/execute/PathingCore.java");
        assertTrue(text.contains("WorldMutationPolicy.allowsBreaking(BreakCause.NAVIGATION)"));
        int check = text.indexOf("requiredObstacleBreaks(lastResult.path()) > 0");
        assertTrue(check >= 0 && check < text.indexOf("executor.tick()"));
        assertTrue(text.substring(check, text.indexOf("executor.tick()")).contains("executor.stop()"));
    }

    @Test void emergencyRecoveryCannotStartMiningPlayerAssets() throws Exception {
        for (String name : new String[]{"UnstuckChain", "BreathChain"}) {
            String text = source("engine/src/main/java/com/mineagent/engine/survival/" + name + ".java");
            assertFalse(text.contains("BlockDigger.startBreaking"), name);
            assertFalse(text.contains("handleBlockBreakAction"), name);
            assertFalse(text.contains(".leftClick()"), name);
            assertTrue(text.contains("SafetyMessages.recoveryPreservesWorld()"), name);
        }
    }

    @Test void unexpectedMovementObstructionsCannotAcquireExplicitMiningAuthority() throws Exception {
        String nav = source("engine/src/main/java/com/mineagent/engine/pathing/execute/PathingCore.java");
        String tick = nav.substring(nav.indexOf("public void tick()"), nav.indexOf("private void tickSearch()"));
        assertTrue(tick.contains("WorldMutationPolicy.runWithBreakCause(BreakCause.NAVIGATION"));
        String dig = source("engine/src/main/java/com/mineagent/engine/task/BlockDigger.java");
        assertTrue(dig.contains("startBreakingDetailed(player, pos, WorldMutationPolicy.currentBreakCause())"));
        assertTrue(dig.contains("assessBreak(player, pos, WorldMutationPolicy.currentBreakCause())"));
        String explicit = dig.substring(dig.indexOf("BreakCause cause)"), dig.indexOf("BlockState state = player.level().getBlockState(pos)"));
        assertTrue(explicit.contains("assessBreak(player, pos, cause)"));
    }

    @Test void blockUseCannotBypassPacketListenerWorldPermissions() throws Exception {
        String text = source("engine/src/main/java/com/mineagent/engine/act/Interaction.java");
        String block = text.substring(text.indexOf("public static InteractionResult interactBlock"),
                text.indexOf("public static InteractionResult interactEntity"));
        for (String check : new String[]{"level.mayInteract(player, pos)",
                "level.getWorldBorder().isWithinBounds(pos)", "player.canInteractWithBlock(pos, 0.0)"}) {
            int at = block.indexOf(check);
            assertTrue(at >= 0 && at < block.indexOf("player.gameMode.useItemOn"), check);
        }
        String entity = text.substring(text.indexOf("public static InteractionResult interactEntity"),
                text.indexOf("public static boolean openContainer"));
        assertTrue(entity.contains("player.canInteractWithEntity(target, 0.0)"));
        String dig = source("engine/src/main/java/com/mineagent/engine/task/BlockDigger.java");
        assertTrue(dig.contains("!player.canInteractWithBlock(pos, 0.0)"));
        assertFalse(dig.contains("!player.canInteractWithBlock(pos, 1.0) && distance > reach"));
    }

    @Test void explicitMiningRetainsVanillaStartAndFinalMutationRevalidation() throws Exception {
        String text = source("engine/src/main/java/com/mineagent/engine/task/BlockDigger.java");
        assertTrue(text.contains("WorldMutationPolicy.allowsBreaking(cause)"));
        assertTrue(text.contains("AUTONOMOUS_MUTATION_DENIED"));
        assertTrue(text.contains("START_DESTROY_BLOCK"));
        assertTrue(text.contains("prepareBestTool(player, state)"));
        assertTrue(text.contains("level.mayInteract(player, pos)"));
        assertTrue(text.contains("BlockTargeting.findVisibleHit(player, pos, reach)"));
        Path events = ROOT.resolve("engine/src/main/java/com/mineagent/engine/safety/WorldMutationSafetyEvents.java");
        assertTrue(Files.exists(events), "Final block mutation guard is missing");
        String guard = Files.readString(events);
        assertTrue(guard.contains("BlockEvent.BreakEvent"));
        assertTrue(guard.contains("BlockDigger.canBreak(player, event.getPos())"));
        assertTrue(guard.contains("event.setCanceled(true)"));
    }
}
