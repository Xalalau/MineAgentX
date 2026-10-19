package com.mineagent.engine.safety;

import com.mineagent.api.safety.CombatPolicy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.alchemy.PotionContents;

/** Emission mitigation, not per-victim splash/lingering effect filtering. */
public final class HarmfulPotionSafety {
    private HarmfulPotionSafety() {}

    public static boolean permitsUse(Player player, ItemStack stack) {
        if (!(stack.getItem() instanceof ThrowablePotionItem) || !CombatSafety.isCompanion(player)) return true;
        var policy = CombatPolicy.current();
        if (policy.allowAttackingPlayers() && policy.allowAttackingCompanions()) return true;
        // Delayed effects can lose their author; mixed/custom effects must be
        // checked too. Do not emit unless every effect is known beneficial.
        for (var effect : stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects()) {
            if (effect.getEffect().value().getCategory() != MobEffectCategory.BENEFICIAL) return false;
        }
        return true;
    }
}
