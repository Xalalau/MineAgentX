package com.mineagent.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source wiring guards supplement the executable policy, config and feature tests. */
class RuntimeIntegrationWiringTest {
    private static final Path ROOT = Path.of(System.getProperty("mineagentx.projectRoot", "."));
    private static String source(String path) throws Exception {
        return Files.readString(ROOT.resolve(path));
    }
    private String engine() throws Exception {
        return source("engine/src/main/java/com/mineagent/engine/MineAgentEngine.java");
    }

    @Test void configInitializationAndReloadApplyServerLanguageAndCombat() throws Exception {
        String text = engine();
        assertTrue(text.contains("applyConfiguration(MineAgentConfig.load(configDir))"));
        assertTrue(text.contains("applyConfiguration(MineAgentConfig.load(configDirPath))"));
        assertTrue(text.contains("RuntimeLanguage.configure(next.language())"));
        assertTrue(text.contains("CombatPolicy.configure(new CombatPolicy(allowAttacks, allowAttacks))"));
    }
    @Test void connectionAndCommandUpdatesPreserveRuntimePolicies() throws Exception {
        assertFalse(engine().contains("config.survival(), config.pathfinding());"),
                "Four-field config updates discard configured language, safety, vision and reflection");
    }

    @Test void deliveredFeatureToolsAreRegistered() throws Exception {
        String text = source("tools/src/main/java/com/mineagent/tools/ToolRegistration.java");
        assertTrue(text.contains("new IdentityMemoryTool()"));
        assertTrue(text.contains("new ConstructionTool(MinecraftConstructionBridge::open, null)"));
        assertTrue(text.contains("new ExecuteConstructionTool(MinecraftConstructionBridge::open)"));
    }

    @Test void temporaryCleanupCannotBorrowExplicitMiningAuthority() throws Exception {
        String text = source("engine/src/main/java/com/mineagent/engine/planning/TemporaryBlockLedger.java");
        int wrapper = text.indexOf("public synchronized boolean tickIdle()");
        int body = text.indexOf("private boolean tickIdleWithinPolicy()");
        assertTrue(wrapper >= 0 && body > wrapper, "Cleanup needs a constrained entry point");
        String entry = text.substring(wrapper, body);
        assertTrue(entry.contains("WorldMutationPolicy.runWithBreakCause(BreakCause.TEMPORARY_CLEANUP"));
        assertTrue(entry.contains("tickIdleWithinPolicy()"));
    }

}
