package com.mineagent.safety;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Structural integration guards supplement the executable bilingual catalog tests. */
class CombatLocalizationWiringTest {
    private static final Path ROOT = Files.isDirectory(Path.of("tools")) ? Path.of("") : Path.of("..");
    private String source(String relative) throws Exception { return Files.readString(ROOT.resolve(relative)); }

    @Test void toolsLocalizeDescriptionsSchemasAndValidationResponses() throws Exception {
        for (String[] tool : new String[][]{
                {"combat/MeleeAttackTool", "melee.description", "param.entity.attack"},
                {"combat/RangedAttackTool", "ranged.description", "param.entity.shoot"},
                {"InteractEntityTool", "entity.description", "param.button"}}) {
            String text = source("tools/src/main/java/com/mineagent/tools/" + tool[0] + ".java");
            assertTrue(text.contains("SafetyMessages.text(\"" + tool[1] + "\")"), tool[0]);
            assertTrue(text.contains("SafetyMessages.text(\"" + tool[2] + "\")"), tool[0]);
            assertFalse(text.contains("Missing required parameter"), tool[0]);
            assertFalse(text.contains("must be a non-negative integer"), tool[0]);
            assertFalse(text.contains("ToolArgs.errorJson(\""), tool[0]);
        }
    }

    @Test void physicalRecoveryAndMiningDiagnosticsUseTheSharedCatalog() throws Exception {
        for (String name : new String[]{"UnstuckChain", "BreathChain"}) {
            String text = source("engine/src/main/java/com/mineagent/engine/survival/" + name + ".java");
            assertFalse(text.contains("bodyLog.report(\""), name);
        }
        String dig = source("engine/src/main/java/com/mineagent/engine/task/BlockDigger.java");
        for (String raw : new String[]{"server game mode did not accept START_DESTROY_BLOCK",
                "player or target is null", "target already contains air", "server mayInteract denied target=",
                "game mode restricted target=", "no target outline face is visible"}) {
            assertFalse(dig.contains(raw), raw);
        }
        assertTrue(dig.contains("SafetyMessages.text(\"break.visible_face\")"));
    }

    @Test void tasksAndAutomaticDefenseLocalizeEveryOwnedOutcome() throws Exception {
        for (String[] task : new String[][]{
                {"MeleeAttackTask", "melee"}, {"RangedAttackTask", "ranged"},
                {"InteractEntityTask", "entity"}}) {
            String text = source("engine/src/main/java/com/mineagent/engine/task/" + task[0] + ".java");
            for (String suffix : new String[]{"success", "timeout", "failure", "progress"}) {
                assertTrue(text.contains("SafetyMessages.text(\"" + task[1] + "." + suffix + "\""), task[0] + suffix);
            }
            assertFalse(text.contains("Target moved to another dimension"), task[0]);
            assertFalse(text.contains("failReason = \""), task[0]);
            assertFalse(text.contains("fail(\""), task[0]);
        }
        String defense = source("engine/src/main/java/com/mineagent/engine/survival/MobDefenseChain.java");
        assertFalse(defense.contains("bodyLog.report(\""));
        assertTrue(defense.contains("SafetyMessages.text(\"defense.creeper\")"));
        assertTrue(defense.contains("SafetyMessages.text(\"defense.too_long\")"));
    }
}
