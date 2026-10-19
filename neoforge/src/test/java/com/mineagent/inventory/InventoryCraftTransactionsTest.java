package com.mineagent.inventory;

import com.mineagent.tools.inventory.InventoryCraftTransactions;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InventoryCraftTransactionsTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static boolean craft(Inventory inventory, int[] slots, List<ItemStack> inputs,
                                 ItemStack output, List<ItemStack> remainders) {
        return InventoryCraftTransactions.craft(inventory, slots, inputs, output, remainders);
    }

    @Test void recipeBucketsReturnWithoutDroppingOrLosingComponents() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.MILK_BUCKET));
        ItemStack bucket = new ItemStack(Items.BUCKET);
        bucket.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("original"));
        assertTrue(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.MILK_BUCKET)),
                new ItemStack(Items.CAKE), List.of(bucket)));
        assertTrue(ItemStack.matches(bucket, inventory.getItem(0)));
        assertTrue(inventory.getItem(1).is(Items.CAKE));
    }

    @Test void offhandMergeCountsOnceAndCannotUseEmptyArmorSlots() {
        Inventory inventory = new Inventory(null);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.OAK_PLANKS, 60));
        inventory.selected = 0;
        assertTrue(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.OAK_LOG)),
                new ItemStack(Items.OAK_PLANKS, 4), List.of(ItemStack.EMPTY)));
        assertEquals(64, inventory.getItem(Inventory.SLOT_OFFHAND).getCount());
        assertEquals(1, inventory.getItem(0).getCount());
        assertFalse(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.OAK_LOG)),
                new ItemStack(Items.OAK_PLANKS, 65), List.of(ItemStack.EMPTY)));
        assertEquals(1, inventory.getItem(0).getCount());
        assertTrue(inventory.getItem(36).isEmpty());
    }

    @Test void failedNotificationKeepsTruthfulCommittedCraftOutcome() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG));
        var outcome = assertDoesNotThrow(() -> InventoryCraftTransactions.craftAndNotify(inventory,
                new int[]{0}, List.of(new ItemStack(Items.OAK_LOG)), new ItemStack(Items.OAK_PLANKS, 4),
                List.of(ItemStack.EMPTY), () -> { throw new IllegalStateException("test hook"); }));
        assertTrue(outcome.committed());
        assertFalse(outcome.notified());
        assertTrue(inventory.getItem(0).is(Items.OAK_PLANKS));
        assertEquals(4, inventory.getItem(0).getCount());
    }

    @Test void vanillaRecipeRemainderContractFeedsRealInventoryTransaction() {
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC, new ItemStack(Items.CAKE),
                net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.crafting.Ingredient.of(Items.MILK_BUCKET)));
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.MILK_BUCKET));
        var input = net.minecraft.world.item.crafting.CraftingInput.of(1, 1,
                List.of(inventory.getItem(0).copyWithCount(1)));
        assertTrue(recipe.matches(input, null));
        assertTrue(craft(inventory, new int[]{0}, input.items(),
                recipe.assemble(input, null), recipe.getRemainingItems(input)));
        assertTrue(inventory.getItem(0).is(Items.BUCKET));
        assertTrue(inventory.getItem(1).is(Items.CAKE));
    }

    @Test void repeatedSourceSlotCannotSpendTheSameIngredientTwice() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG));
        assertFalse(craft(inventory, new int[]{0, 0}, List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.OAK_LOG)),
                new ItemStack(Items.OAK_PLANKS, 8), List.of(ItemStack.EMPTY, ItemStack.EMPTY)));
        assertTrue(inventory.getItem(0).is(Items.OAK_LOG));
        assertEquals(1, inventory.getItem(0).getCount());
    }

    @Test void remainderCapacityFailureRollsBackWholeBatch() {
        Inventory inventory = new Inventory(null);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        inventory.setItem(0, new ItemStack(Items.MILK_BUCKET));
        assertFalse(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.MILK_BUCKET)),
                new ItemStack(Items.CAKE), List.of(new ItemStack(Items.BUCKET))));
        assertTrue(inventory.getItem(0).is(Items.MILK_BUCKET));
        assertEquals(1, inventory.getItem(0).getCount());
    }

    @Test void oversizedRemainderSplitsIntoLegalStacks() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.MILK_BUCKET));
        assertTrue(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.MILK_BUCKET)),
                new ItemStack(Items.CAKE), List.of(new ItemStack(Items.BUCKET, 35))));
        assertEquals(16, inventory.getItem(0).getCount());
        assertEquals(16, inventory.getItem(1).getCount());
        assertEquals(3, inventory.getItem(2).getCount());
        assertTrue(inventory.getItem(3).is(Items.CAKE));
    }

    @Test void toolPlanAlignsBackingSlotsAfterMinecraftTrimsEmptyGridBorders() {
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("",
                net.minecraft.world.item.crafting.CraftingBookCategory.MISC, new ItemStack(Items.OAK_PLANKS, 4),
                net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_LOG)));
        Inventory inventory = new Inventory(null);
        inventory.setItem(7, new ItemStack(Items.OAK_LOG, 2));
        Object plan = assertDoesNotThrow(() -> {
            var method = com.mineagent.tools.crafting.CraftTool.class.getDeclaredMethod("createPlan",
                    Inventory.class, net.minecraft.world.item.crafting.CraftingRecipe.class, int.class,
                    net.minecraft.world.level.Level.class);
            method.setAccessible(true);
            return method.invoke(null, inventory, recipe, 2, null);
        });
        assertNotNull(plan);
        var input = (net.minecraft.world.item.crafting.CraftingInput) planField(plan, "input");
        var slots = (int[]) planField(plan, "inventorySlots");
        assertEquals(input.size(), slots.length);
        assertArrayEquals(new int[]{7}, slots);
        assertTrue(craft(inventory, slots, input.items(), recipe.assemble(input, null), recipe.getRemainingItems(input)));
        assertEquals(1, inventory.getItem(7).getCount());
        assertEquals(4, inventory.getItem(0).getCount());
    }

    private static Object planField(Object plan, String name) {
        return assertDoesNotThrow(() -> {
            var accessor = plan.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(plan);
        });
    }

    @Test void consumedLastIngredientFreesSpaceForOutput() {
        Inventory inventory = new Inventory(null);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        inventory.setItem(0, new ItemStack(Items.OAK_LOG));
        assertTrue(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.OAK_LOG)),
                new ItemStack(Items.OAK_PLANKS, 4), List.of(ItemStack.EMPTY)));
        assertTrue(inventory.getItem(0).is(Items.OAK_PLANKS));
        assertEquals(4, inventory.getItem(0).getCount());
    }

    @Test void fullInventoryDoesNotConsumeInputsWhenOutputCannotFit() {
        Inventory inventory = new Inventory(null);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        assertFalse(craft(inventory, new int[]{0}, List.of(new ItemStack(Items.OAK_LOG)),
                new ItemStack(Items.OAK_PLANKS, 4), List.of(ItemStack.EMPTY)));
        assertEquals(2, inventory.getItem(0).getCount());
        assertTrue(inventory.getItem(0).is(Items.OAK_LOG));
    }
}
