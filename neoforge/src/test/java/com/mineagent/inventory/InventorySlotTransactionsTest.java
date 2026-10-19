package com.mineagent.inventory;

import com.mineagent.tools.inventory.InventorySlotTransactions;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventorySlotTransactionsTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static InventorySlotTransactions.Outcome move(Slot from, Slot to, int count) {
        return InventorySlotTransactions.move(from, to, count, null);
    }

    @Test void partialInsertionReturnsUnacceptedItemsBeforeTakeHook() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 10), ItemStack.EMPTY);
        int[] taken = {0};
        Slot source = new Slot(inventory, 0, 0, 0) {
            @Override public void onTake(Player player, ItemStack stack) { taken[0] += stack.getCount(); }
        };
        Slot destination = new Slot(inventory, 1, 0, 0) {
            @Override public ItemStack safeInsert(ItemStack stack, int count) {
                return super.safeInsert(stack, Math.min(count, 3));
            }
        };
        move(source, destination, 10);
        assertEquals(7, inventory.getItem(0).getCount());
        assertEquals(3, inventory.getItem(1).getCount());
        assertEquals(3, taken[0]);
    }

    @Test void wholeSwapPreservesComponentsAndCallsBothTakeHooks() {
        var stone = new ItemStack(Items.STONE, 4);
        stone.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("kept"));
        var expected = stone.copy();
        var inventory = new SimpleContainer(stone, new ItemStack(Items.DIRT, 3));
        int[] callbacks = {0};
        Slot source = new Slot(inventory, 0, 0, 0) {
            @Override public void onTake(Player player, ItemStack stack) { callbacks[0]++; }
        };
        Slot destination = new Slot(inventory, 1, 0, 0) {
            @Override public void onTake(Player player, ItemStack stack) { callbacks[0]++; }
        };
        move(source, destination, 4);
        assertTrue(inventory.getItem(0).is(Items.DIRT));
        assertEquals(3, inventory.getItem(0).getCount());
        assertTrue(ItemStack.matches(expected, inventory.getItem(1)));
        assertEquals(2, callbacks[0]);
    }

    @Test void cancelledDropRestoresExtractedComponentsEvenIfHookMutatesAttempt() {
        ItemStack original = new ItemStack(Items.STONE, 10);
        original.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("keep"));
        var inventory = new SimpleContainer(original.copy());
        assertEquals(0, InventorySlotTransactions.drop(new Slot(inventory, 0, 0, 0), 5, null,
                stack -> { stack.setCount(0); return false; }));
        assertTrue(ItemStack.matches(original, inventory.getItem(0)));
    }

    @Test void resultLikeSlotDoesNotChargeRecipeForPartiallyRefusedDelivery() {
        var inventory = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 4), ItemStack.EMPTY);
        int[] consumed = {0};
        Slot result = new Slot(inventory, 0, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public void onTake(Player player, ItemStack stack) { consumed[0]++; }
        };
        Slot destination = new Slot(inventory, 1, 0, 0) {
            @Override public ItemStack safeInsert(ItemStack stack, int count) {
                return super.safeInsert(stack, Math.min(count, 3));
            }
        };
        move(result, destination, 4);
        assertEquals(0, consumed[0]);
        assertEquals(4, inventory.getItem(0).getCount());
        assertTrue(inventory.getItem(1).isEmpty());
    }

    @Test void swapCannotBypassPartialExtractionRules() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 4), new ItemStack(Items.DIRT, 3));
        Slot source = new Slot(inventory, 0, 0, 0) {
            @Override public ItemStack remove(int count) { return super.remove(Math.min(count, 2)); }
        };
        move(source, new Slot(inventory, 1, 0, 0), 4);
        assertTrue(inventory.getItem(0).is(Items.STONE));
        assertEquals(4, inventory.getItem(0).getCount());
        assertTrue(inventory.getItem(1).is(Items.DIRT));
        assertEquals(3, inventory.getItem(1).getCount());
    }

    @Test void partialSourceDoesNotReportExplicitRequestComplete() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 10), ItemStack.EMPTY);
        var outcome = move(new Slot(inventory, 0, 0, 0), new Slot(inventory, 1, 0, 0), 64);
        var report = outcome.result(64);
        assertEquals(10, report.get("count").getAsInt());
        assertEquals(64, report.get("requested").getAsInt());
        assertEquals(54, report.get("remaining").getAsInt());
        assertFalse(report.get("complete").getAsBoolean());
    }

    @Test void menuAliasOfSameBackingSlotDoesNotMoveAnything() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 10));
        var outcome = move(new Slot(inventory, 0, 0, 0), new Slot(inventory, 0, 10, 10), 5);
        assertEquals(0, outcome.moved());
        assertEquals("same_slot", outcome.code());
        assertEquals(10, inventory.getItem(0).getCount());
    }

    @Test void partialExtractionReportsOnlyWhatActuallyMoved() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 10), ItemStack.EMPTY);
        Slot source = new Slot(inventory, 0, 0, 0) {
            @Override public ItemStack remove(int count) { return super.remove(Math.min(count, 2)); }
        };
        var outcome = move(source, new Slot(inventory, 1, 0, 0), 8);
        assertEquals(2, outcome.moved());
        assertTrue(outcome.verified());
        assertEquals(8, inventory.getItem(0).getCount());
        assertEquals(2, inventory.getItem(1).getCount());
    }

    @Test void componentDistinctStacksNeverMerge() {
        var named = new ItemStack(Items.STONE, 4);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("named"));
        var inventory = new SimpleContainer(named.copy(), new ItemStack(Items.STONE, 3));
        var outcome = move(new Slot(inventory, 0, 0, 0), new Slot(inventory, 1, 0, 0), 2);
        assertEquals(0, outcome.moved());
        assertTrue(ItemStack.matches(named, inventory.getItem(0)));
        assertEquals(3, inventory.getItem(1).getCount());
    }

    @Test void acceptedDropCountsExtractedItemsOnly() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 10));
        java.util.List<ItemStack> delivered = new java.util.ArrayList<>();
        int dropped = InventorySlotTransactions.drop(new Slot(inventory, 0, 0, 0), 6, null,
                stack -> delivered.add(stack.copy()));
        assertEquals(6, dropped);
        assertEquals(4, inventory.getItem(0).getCount());
        assertEquals(6, delivered.getFirst().getCount());
    }

    @Test void refusedEmptyOperationNeverReportsCompletion() {
        var report = new InventorySlotTransactions.Outcome(0, false, true, "slot_refused").result(0);
        assertFalse(report.get("success").getAsBoolean());
        assertFalse(report.get("complete").getAsBoolean());
    }

    @Test void dropReportCannotCompleteWhenLiveInventoryDisagrees() {
        var report = InventorySlotTransactions.dropResult("minecraft:stone", 5, 5, 10, 6, true, false);
        assertFalse(report.get("success").getAsBoolean());
        assertFalse(report.get("complete").getAsBoolean());
        assertFalse(report.get("postcondition_verified").getAsBoolean());
        assertEquals(5, report.get("dropped").getAsInt());
        assertEquals(6, report.get("carried_after").getAsInt());
    }

    @Test void transferFitsOnlyAvailableSpace() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 10), new ItemStack(Items.STONE, 62));
        move(new Slot(inventory, 0, 0, 0), new Slot(inventory, 1, 0, 0), 10);
        assertEquals(8, inventory.getItem(0).getCount());
        assertEquals(64, inventory.getItem(1).getCount());
    }

    @Test void swapCannotExtractLockedDestination() {
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 4), new ItemStack(Items.DIRT, 3));
        Slot locked = new Slot(inventory, 1, 0, 0) {
            @Override public boolean mayPickup(Player player) { return false; }
        };
        move(new Slot(inventory, 0, 0, 0), locked, 4);
        assertEquals(4, inventory.getItem(0).getCount());
        assertTrue(inventory.getItem(0).is(Items.STONE));
        assertEquals(3, inventory.getItem(1).getCount());
        assertTrue(inventory.getItem(1).is(Items.DIRT));
    }
}
