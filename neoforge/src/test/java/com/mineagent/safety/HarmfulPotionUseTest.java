package com.mineagent.safety;

import com.mineagent.api.safety.CombatPolicy;
import com.mineagent.engine.act.Interaction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HarmfulPotionUseTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.start();
    }
    @AfterEach void reset() { CombatPolicy.configure(CombatPolicy.DEFAULTS); }

    @Test void rawGameModeRefusesLingeringWeaknessBeforeItemUse() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var stack = PotionContents.createItemStack(Items.LINGERING_POTION, Potions.WEAKNESS);
        var player = HarmfulPotionFixtures.player(stack);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(com.mineagent.engine.safety.CombatSafetyEvents.class);
        try {
            // The real game-mode hook returns null when item use may continue.
            assertEquals(net.minecraft.world.InteractionResult.FAIL,
                    net.neoforged.neoforge.common.CommonHooks.onItemRightClick(player, InteractionHand.OFF_HAND));
            var mode = new com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode(player);
            assertEquals(net.minecraft.world.InteractionResult.FAIL,
                    mode.useItem(player, null, stack, InteractionHand.OFF_HAND),
                    "actual game-mode consumer must stop before ThrowablePotionItem.use");
            assertEquals(1, stack.getCount());
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(com.mineagent.engine.safety.CombatSafetyEvents.class);
        }
    }

    @Test void onlyUnrestrictedPolicyAllowsHarmfulAreaEmission() throws Exception {
        for (boolean players : new boolean[]{false, true}) {
            for (boolean companions : new boolean[]{false, true}) {
                CombatPolicy.configure(new CombatPolicy(players, companions));
                var player = HarmfulPotionFixtures.player(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.POISON));
                assertEquals(players && companions, Interaction.useItem(player, InteractionHand.OFF_HAND));
                assertEquals(players && companions ? 1 : 0,
                        ((HarmfulPotionFixtures.RecordingGameMode) player.gameMode).uses);
            }
        }
    }

    @Test void harmfulBaseCustomMixedAndNeutralEffectsAreNotEmitted() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        for (var item : new net.minecraft.world.item.Item[]{Items.SPLASH_POTION, Items.LINGERING_POTION}) {
            for (var potion : java.util.List.of(Potions.POISON, Potions.SLOWNESS, Potions.WEAKNESS, Potions.HARMING)) {
                var player = HarmfulPotionFixtures.player(PotionContents.createItemStack(item, potion));
                assertFalse(Interaction.useItem(player, InteractionHand.MAIN_HAND), potion.toString());
            }
            for (var effect : java.util.List.of(net.minecraft.world.effect.MobEffects.POISON, net.minecraft.world.effect.MobEffects.GLOWING)) {
                var stack = PotionContents.createItemStack(item, Potions.HEALING);
                stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                        new PotionContents(Potions.HEALING).withEffectAdded(new net.minecraft.world.effect.MobEffectInstance(effect, 200)));
                assertFalse(Interaction.useItem(HarmfulPotionFixtures.player(stack), InteractionHand.MAIN_HAND));
            }
        }
    }

    @Test void safeThrowablesDrinkingAndCommonItemsRemainUsable() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var stacks = java.util.List.of(
                PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING),
                PotionContents.createItemStack(Items.LINGERING_POTION, Potions.SWIFTNESS),
                PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER),
                PotionContents.createItemStack(Items.POTION, Potions.POISON),
                new net.minecraft.world.item.ItemStack(Items.BREAD),
                new net.minecraft.world.item.ItemStack(Items.SHIELD),
                new net.minecraft.world.item.ItemStack(Items.SNOWBALL));
        for (var stack : stacks) {
            var player = HarmfulPotionFixtures.player(stack);
            assertTrue(Interaction.useItem(player, InteractionHand.MAIN_HAND), stack.toString());
            assertEquals(1, ((HarmfulPotionFixtures.RecordingGameMode) player.gameMode).uses);
        }
    }

    @Test void ordinaryHumanPotionUseIsNotDeniedByCompanionPolicy() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var player = HarmfulPotionFixtures.player(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.POISON));
        player.gameMode = new net.minecraft.server.level.ServerPlayerGameMode(player);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(com.mineagent.engine.safety.CombatSafetyEvents.class);
        try {
            assertNull(net.neoforged.neoforge.common.CommonHooks.onItemRightClick(player, InteractionHand.MAIN_HAND));
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(com.mineagent.engine.safety.CombatSafetyEvents.class);
        }
    }

    @Test void genericUseRefusesPoisonBeforeGameModeAndPreservesStack() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var stack = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.POISON);
        var before = stack.copy();
        var player = HarmfulPotionFixtures.player(stack);
        assertFalse(Interaction.useItem(player, InteractionHand.MAIN_HAND),
                "generic use_item must refuse harmful emission before vanilla item use");
        assertEquals(0, ((HarmfulPotionFixtures.RecordingGameMode) player.gameMode).uses);
        assertTrue(net.minecraft.world.item.ItemStack.matches(before, stack));
    }
}
