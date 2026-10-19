package com.mineagent.engine.safety;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;

/**
 * Final server-side guards, including raw input, sweep victims and in-flight
 * projectiles after configuration changes. Registered by NeoForge on the game bus.
 */
@EventBusSubscriber(modid = "mineagent")
public final class CombatSafetyEvents {
    private CombatSafetyEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CombatSafety.isCompanion(player)
                && (!CombatSafety.canAttack(player, event.getTarget())
                    || !player.canInteractWithEntity(event.getTarget(), 0.0)
                    || !player.hasLineOfSight(event.getTarget()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onSweep(SweepAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CombatSafety.isCompanion(player)
                && CombatSafety.hasProtectedSweepBystander(player, event.getTarget())) {
            // Vanilla knocks sweep victims back BEFORE calling hurt(). Disabling
            // just the sweep preserves the intended primary hit without collateral.
            event.setSweeping(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        // DamageSource#getEntity is the responsible shooter, not just the arrow.
        if (CombatSafety.isCompanion(event.getSource().getEntity())
                && !CombatSafety.permitsTarget(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
