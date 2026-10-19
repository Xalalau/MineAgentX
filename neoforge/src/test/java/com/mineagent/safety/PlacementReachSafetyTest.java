package com.mineagent.safety;

import com.mineagent.engine.act.BlockTargeting;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementReachSafetyTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void productionPlacementChecksActuallyClickedSupportWithZeroTolerance() throws Exception {
        var p = PlacementReachFixture.player();
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(1.0);
        var target = new net.minecraft.core.BlockPos(1, 0, 0);
        var support = target.east();
        p.world.blocks.put(support, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        p.inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.level.block.Blocks.COBBLESTONE, 2));
        assertFalse(p.canInteractWithBlock(support, 0.0));
        assertTrue(p.canInteractWithBlock(support, 1.0));
        p.reachChecks = 0;
        var attempt = com.mineagent.engine.act.Placement.placeHeldBlock(p, target, net.minecraft.world.InteractionHand.MAIN_HAND);
        assertFalse(attempt.placed());
        assertTrue(p.reachChecks > 0, "actual clicked-support validation must execute");
        assertEquals(0.0, p.lastTolerance, "packet allowance is not physical placement reach");
        assertEquals(2, p.inventory.getItem(0).getCount());
        assertTrue(p.world.getBlockState(target).isAir());
    }
    @Test void targetAdmissionUsesSameLivePhysicalRadius() throws Exception {
        var p = PlacementReachFixture.player();
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(1.0);
        var target = new net.minecraft.core.BlockPos(2, 0, 0);
        p.inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.level.block.Blocks.COBBLESTONE, 2));
        var attempt = com.mineagent.engine.act.Placement.placeHeldBlock(p, target, net.minecraft.world.InteractionHand.MAIN_HAND);
        assertFalse(attempt.placed());
        assertEquals("target is outside interaction reach", attempt.reason());
        assertEquals(0, p.reachChecks);
        assertEquals(2, p.inventory.getItem(0).getCount());
    }
    @Test void callerSuppliedRayRadiusCannotExpandLivePhysicalReach() throws Exception {
        var p = PlacementReachFixture.player();
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(1.0);
        var support = new net.minecraft.core.BlockPos(2, 0, 0);
        p.world.blocks.put(support, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        assertTrue(BlockTargeting.findVisibleFace(p, support, net.minecraft.core.Direction.WEST, 10.0).isEmpty());
    }
    @Test void defaultAndCreativeControlsStillReachTheVanillaPlacementBoundary() throws Exception {
        for (boolean creative : new boolean[] {false, true}) {
            var p = PlacementReachFixture.player();
            p.creative = creative;
            var mode = (PlacementReachFixture.TestMode) p.gameMode;
            mode.reach = creative ? 5.0 : 4.5;
            p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(creative ? 5.0 : 4.5);
            var target = new net.minecraft.core.BlockPos(creative ? 5 : 4, 0, 0);
            var support = target.below();
            p.world.blocks.put(support, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            // Explicit building is not subject to the automatic material opt-in.
            p.inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.level.block.Blocks.TNT, 2));
            assertThrows(PlacementReachFixture.ReachedVanillaUse.class,
                    () -> com.mineagent.engine.act.Placement.placeHeldBlock(p, target, net.minecraft.world.InteractionHand.MAIN_HAND));
            assertEquals(1, mode.useCalls);
            assertEquals(support, mode.hit.getBlockPos());
            assertEquals(net.minecraft.core.Direction.UP, mode.hit.getDirection());
            assertEquals(0.0, p.lastTolerance);
            assertFalse(p.sneaking, "sneaking restored even at a throwing boundary");
            assertEquals(2, p.inventory.getItem(0).getCount());
        }
    }
    @Test void customLimitCanNarrowButNeverExpandDefaultOrCreativeAttribute() throws Exception {
        var p = PlacementReachFixture.player();
        var mode = (PlacementReachFixture.TestMode) p.gameMode;
        assertEquals(4.5, BlockTargeting.interactionReach(p));
        mode.reach = 5.0;
        assertEquals(4.5, BlockTargeting.interactionReach(p));
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(5.0);
        assertEquals(5.0, BlockTargeting.interactionReach(p));
        mode.reach = 3.0;
        assertEquals(3.0, BlockTargeting.interactionReach(p));
        var support = new net.minecraft.core.BlockPos(4, 0, 0);
        p.world.blocks.put(support, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        assertTrue(BlockTargeting.findVisibleFace(p, support, net.minecraft.core.Direction.WEST, 100.0).isEmpty());
        mode.reach = 5.0;
        assertTrue(BlockTargeting.findVisibleFace(p, support, net.minecraft.core.Direction.WEST, 3.0).isEmpty());
        assertTrue(BlockTargeting.findVisibleFace(p, support, net.minecraft.core.Direction.WEST, 5.0).isPresent());
    }
    @Test void realFakeGameModeKeepsItsIntentionalSurvivalAndCreativeCaps() throws Exception {
        var p = PlacementReachFixture.player();
        var mode = new com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode(p);
        p.gameMode = mode;
        assertEquals(4.5, mode.getReachDistance());
        assertEquals(4.5, BlockTargeting.interactionReach(p));
        mode.setCreativeReach(true);
        assertEquals(5.0, mode.getReachDistance());
        assertEquals(4.5, BlockTargeting.interactionReach(p));
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(5.0);
        assertEquals(5.0, BlockTargeting.interactionReach(p));
        mode.setCreativeReach(false);
        assertEquals(4.5, BlockTargeting.interactionReach(p));
        PlacementReachFixture.field(mode, net.minecraft.server.level.ServerPlayerGameMode.class,
                "gameModeForPlayer", net.minecraft.world.level.GameType.CREATIVE);
        assertEquals(5.0, mode.getReachDistance());
        assertEquals(5.0, BlockTargeting.interactionReach(p));
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(1.0);
        assertEquals(1.0, BlockTargeting.interactionReach(p));
    }
    @Test void physicalRadiusReadsLiveAttributeInsteadOfCustomReachOverride() throws Exception {
        var p = PlacementReachFixture.player();
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(1.0);
        assertEquals(1.0, p.blockInteractionRange());
        assertEquals(1.0, BlockTargeting.interactionReach(p));
        p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(0.5);
        assertEquals(0.5, BlockTargeting.interactionReach(p));
    }
}
