package com.mineagent.safety;

import com.mineagent.engine.act.Placement;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticSupportMaterialTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private static boolean accepts(Block block) throws Exception {
        var selector = Placement.class.getDeclaredMethod("isSafeSupportMaterial", Block.class, BlockState.class);
        selector.setAccessible(true);
        return (boolean) selector.invoke(null, block, block.defaultBlockState());
    }
    @Test void actualInventoryPlanningCountAndAutomaticPlacementUseTheSelector() throws Exception {
        var p = PlacementReachFixture.player();
        p.inventory.setItem(0, new net.minecraft.world.item.ItemStack(Blocks.TNT, 8));
        p.inventory.setItem(1, new net.minecraft.world.item.ItemStack(Blocks.REDSTONE_BLOCK, 9));
        assertEquals(0, Placement.supportBlockCount(p));
        assertFalse(Placement.hasSupportBlock(p));
        assertFalse(Placement.placeAnySupportBlock(p, net.minecraft.core.BlockPos.ZERO));
        assertEquals(0, p.worldReads, "hazardous automatic material must not reach world placement");
        assertEquals(8, p.inventory.getItem(0).getCount());
        assertEquals(9, p.inventory.getItem(1).getCount());
        p.inventory.setItem(8, new net.minecraft.world.item.ItemStack(Blocks.COBBLESTONE, 3));
        p.inventory.setItem(40, new net.minecraft.world.item.ItemStack(Blocks.STONE, 5));
        p.inventory.setItem(36, new net.minecraft.world.item.ItemStack(Blocks.COBBLESTONE, 64));
        assertEquals(8, Placement.supportBlockCount(p));
        assertTrue(Placement.hasSupportBlock(p));
    }
    @Test void pathPlanningAndMovementRemainWiredToCountedAutomaticMaterials() throws Exception {
        // Supplement only: actual inventory/selector consumers are exercised above.
        var root = java.nio.file.Path.of(System.getProperty("mineagentx.projectRoot", ".."));
        var engine = root.resolve("engine/src/main/java/com/mineagent/engine");
        String planner = java.nio.file.Files.readString(engine.resolve("pathing/execute/PathingCore.java"));
        assertTrue(planner.contains("Placement.supportBlockCount("));
        assertEquals(2, planner.split("Placement\\.hasSupportBlock\\(", -1).length - 1);
        for (String path : new String[] {"pathing/moves/Movement.java", "pathing/moves/movements/MovementPillar.java"}) {
            assertTrue(java.nio.file.Files.readString(engine.resolve(path)).contains(".placeAnySupportBlock("), path);
        }
    }
    @Test void conservativeOptInRejectsOtherActiveUnknownAndUnstableCubes() throws Exception {
        for (Block block : new Block[] {Blocks.OBSERVER, Blocks.PISTON, Blocks.RESPAWN_ANCHOR,
                Blocks.SPONGE, Blocks.MAGMA_BLOCK, Blocks.SAND, Blocks.GRAVEL, Blocks.CHEST,
                Blocks.OAK_PLANKS, Blocks.SLIME_BLOCK, Blocks.ICE, Blocks.SCULK, Blocks.FURNACE}) {
            assertFalse(accepts(block), block.toString());
        }
        for (Block block : new Block[] {Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT,
                Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK, Blocks.END_STONE}) {
            assertTrue(accepts(block), block.toString());
        }
    }
    @Test void actualSelectorRejectsExplosiveAndPoweringSupports() throws Exception {
        assertTrue(accepts(Blocks.COBBLESTONE));
        boolean tnt = accepts(Blocks.TNT), redstone = accepts(Blocks.REDSTONE_BLOCK);
        System.out.printf("actual selector: cobblestone=true tnt=%s redstone_block=%s%n", tnt, redstone);
        assertAll(() -> assertFalse(tnt, "TNT must not be automatic scaffolding"),
                () -> assertFalse(redstone, "Redstone block must not be automatic scaffolding"));
    }
}
