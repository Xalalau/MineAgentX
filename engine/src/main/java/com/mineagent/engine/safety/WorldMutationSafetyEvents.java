package com.mineagent.engine.safety;

import com.mineagent.engine.task.BlockDigger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Revalidate physical permissions when vanilla actually commits a progressive break. */
@EventBusSubscriber(modid = "mineagent")
public final class WorldMutationSafetyEvents {
    private WorldMutationSafetyEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && CombatSafety.isCompanion(player)
                && !BlockDigger.canBreak(player, event.getPos())) {
            event.setCanceled(true);
        }
    }
}
