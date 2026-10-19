package com.mineagent.safety;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Supplementary wiring guards; CombatPolicyTest exercises the actual permission logic. */
class CombatWiringTest {
    private static final Path ROOT = Path.of(System.getProperty("mineagentx.projectRoot", ".."));
    private static String source(String path) throws Exception { return Files.readString(ROOT.resolve(path)); }

    @Test void meleeToolsAndGenericAttackRejectProtectedTargetsBeforeDispatch() throws Exception {
        for (String path : new String[]{
                "tools/src/main/java/com/mineagent/tools/combat/MeleeAttackTool.java",
                "tools/src/main/java/com/mineagent/tools/combat/RangedAttackTool.java",
                "tools/src/main/java/com/mineagent/tools/InteractEntityTool.java"}) {
            String text = source(path);
            int check = text.indexOf("CombatSafety.canAttack(sp, target)");
            assertTrue(check >= 0 && check < text.indexOf("TaskDispatch.dispatchAsync"), path);
            assertTrue(text.contains("SafetyMessages.combatDenied()"), path);
        }
        assertTrue(source("tools/src/main/java/com/mineagent/tools/InteractEntityTool.java")
                .contains("\"attack\".equals(button) && !CombatSafety.canAttack"));
    }

    @Test void interactionAndQueuedCombatRevalidateLivePermissions() throws Exception {
        String interaction = source("engine/src/main/java/com/mineagent/engine/act/Interaction.java");
        int start = interaction.indexOf("public static boolean attackEntity");
        String attack = interaction.substring(start);
        assertTrue(attack.indexOf("CombatSafety.canAttack(player, target)") < attack.indexOf("player.attack(target)"));
        assertTrue(attack.contains("player.canInteractWithEntity(target, 0.0)"));
        for (String name : new String[]{"MeleeAttackTask", "RangedAttackTask"}) {
            String text = source("engine/src/main/java/com/mineagent/engine/task/" + name + ".java");
            assertTrue(text.substring(text.indexOf("protected void onStart()"), text.indexOf("protected void onResume()"))
                    .contains("CombatSafety.canAttack"), name + " start");
            assertTrue(text.substring(text.indexOf("protected TaskState onTick()"), text.indexOf("private void tickNavigate()"))
                    .contains("CombatSafety.canAttack"), name + " tick");
        }
        String ranged = source("engine/src/main/java/com/mineagent/engine/task/RangedAttackTask.java");
        assertTrue(ranged.substring(ranged.indexOf("private void tickShoot()"), ranged.indexOf("private void finishShot()"))
                .contains("CombatSafety.canFireAt(sp, target)"));
        assertTrue(ranged.substring(ranged.indexOf("private boolean hasClearShot()"), ranged.indexOf("private void aimAtTarget()"))
                .contains("CombatSafety.canFireAt(sp, target)"));
    }

    @Test void queuedGenericAttackCannotKeepPursuingAProtectedTarget() throws Exception {
        String text = source("engine/src/main/java/com/mineagent/engine/task/InteractEntityTask.java");
        String start = text.substring(text.indexOf("protected void onStart()"), text.indexOf("protected TaskState onTick()"));
        String tick = text.substring(text.indexOf("protected TaskState onTick()"), text.indexOf("private void tickNavigate()"));
        assertTrue(start.contains("\"attack\".equals(record.button) && !CombatSafety.canAttack"));
        assertTrue(start.indexOf("CombatSafety.canAttack") < start.indexOf("nav = new PlayerNav"));
        assertTrue(tick.contains("\"attack\".equals(record.button) && !CombatSafety.canAttack"));
        assertTrue(tick.indexOf("CombatSafety.canAttack") < tick.indexOf("switch (phase)"));
        assertTrue(tick.contains("target.level() != sp.level()"));
    }

    @Test void automaticDefenseRechecksBeforePursuingTargets() throws Exception {
        String text = source("engine/src/main/java/com/mineagent/engine/survival/MobDefenseChain.java");
        String fight = text.substring(text.indexOf("private void tickFighting"), text.indexOf("private void tickFleeing"));
        int check = fight.indexOf("CombatSafety.canAttack(sp, target)");
        assertTrue(check >= 0 && check < fight.indexOf("lookAt(sp, target)"));
    }

    @Test void swordSweepCannotKnockBackProtectedBystandersBeforeDamageCancellation() throws Exception {
        Path path = ROOT.resolve("engine/src/main/java/com/mineagent/engine/safety/CombatSafety.java");
        String text = Files.readString(path);
        assertTrue(text.contains("hasProtectedSweepBystander(ServerPlayer attacker, Entity target)"));
        assertTrue(text.contains("target.getBoundingBox().inflate(1.0, 0.25, 1.0)"));
        assertTrue(text.contains("attacker.distanceToSqr(bystander) < reach * reach"));
        assertTrue(text.contains("!permitsTarget(bystander)"));
        String events = source("engine/src/main/java/com/mineagent/engine/safety/CombatSafetyEvents.java");
        assertTrue(events.contains("onSweep(SweepAttackEvent event)"));
        assertTrue(events.contains("CombatSafety.hasProtectedSweepBystander(player, event.getTarget())"));
        assertTrue(events.contains("event.setSweeping(false)"));
    }

    @Test void damageAndRawInputBypassesHaveAuthoritativeEventGuards() throws Exception {
        Path path = ROOT.resolve("engine/src/main/java/com/mineagent/engine/safety/CombatSafetyEvents.java");
        assertTrue(Files.exists(path), "Authoritative NeoForge event guards are missing");
        String text = Files.readString(path);
        assertTrue(text.contains("@EventBusSubscriber(modid = \"mineagent\")"));
        assertTrue(text.contains("AttackEntityEvent"));
        assertTrue(text.contains("LivingIncomingDamageEvent"));
        assertTrue(text.contains("event.getSource().getEntity()"));
        assertTrue(text.contains("event.setCanceled(true)"));
    }
}
