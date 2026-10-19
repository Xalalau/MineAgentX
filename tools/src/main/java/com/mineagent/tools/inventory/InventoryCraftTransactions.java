package com.mineagent.tools.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded crafting write set prepared from the real carried slots. */
public final class InventoryCraftTransactions {
    private InventoryCraftTransactions() {}

    public record Outcome(boolean committed, boolean notified, String notificationFailure) {}

    public static Outcome craftAndNotify(Inventory inventory, int[] inputSlots, List<ItemStack> inputs,
                                          ItemStack output, List<ItemStack> remainders, Runnable notification) {
        if (!craft(inventory, inputSlots, inputs, output, remainders)) return new Outcome(false, false, null);
        try {
            notification.run();
            return new Outcome(true, true, null);
        } catch (RuntimeException failure) {
            // A notification cannot undo an already delivered physical craft.
            // Preserve its committed count rather than throwing a zero-result
            // tool error that would encourage consuming another batch.
            return new Outcome(true, false, failure.getClass().getSimpleName());
        }
    }

    public static boolean craft(Inventory inventory, int[] inputSlots, List<ItemStack> inputs,
                                ItemStack output, List<ItemStack> remainders) {
        if (output.isEmpty() || inputSlots.length != inputs.size()
                || remainders.size() != inputs.size()) return false;
        Map<Integer, ItemStack> before = new LinkedHashMap<>();
        Map<Integer, ItemStack> after = new LinkedHashMap<>();
        for (int i = 0; i < inputSlots.length; i++) {
            int slot = inputSlots[i];
            if (slot < 0) {
                if (!inputs.get(i).isEmpty()) return false;
                continue;
            }
            if (slot >= Inventory.INVENTORY_SIZE || inputs.get(i).isEmpty()) return false;
            ItemStack current = after.computeIfAbsent(slot, key -> {
                before.put(key, inventory.getItem(key).copy());
                return inventory.getItem(key).copy();
            });
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, inputs.get(i))) return false;
            current.shrink(1);
        }
        for (int i = 0; i < remainders.size(); i++) {
            ItemStack pending = remainders.get(i).copy();
            int slot = inputSlots[i];
            if (!pending.isEmpty() && slot >= 0) {
                ItemStack current = after.get(slot);
                if (current.isEmpty() || ItemStack.isSameItemSameComponents(current, pending)) {
                    int amount = Math.min(pending.getCount(),
                            Math.max(0, inventory.getMaxStackSize(pending) - current.getCount()));
                    after.put(slot, pending.copyWithCount(current.getCount() + amount));
                    pending.shrink(amount);
                }
            }
            if (!insert(inventory, before, after, pending)) return false;
        }
        if (!insert(inventory, before, after, output.copy())) return false;
        for (var entry : before.entrySet()) {
            if (!ItemStack.matches(entry.getValue(), inventory.getItem(entry.getKey()))) return false;
        }
        after.forEach(inventory::setItem);
        inventory.setChanged();
        return true;
    }

    private static boolean insert(Inventory inventory, Map<Integer, ItemStack> before,
                                  Map<Integer, ItemStack> after, ItemStack pending) {
        for (int pass = 0; pass < 2 && !pending.isEmpty(); pass++) {
            for (int index = 0; index <= Inventory.INVENTORY_SIZE && !pending.isEmpty(); index++) {
                // Offhand can merge into an existing stack, as Inventory#add
                // does; empty offhand/armor slots are not generic storage.
                int slot = index == Inventory.INVENTORY_SIZE ? Inventory.SLOT_OFFHAND : index;
                if (slot == Inventory.SLOT_OFFHAND && pass == 1) continue;
                ItemStack current = after.getOrDefault(slot, inventory.getItem(slot));
                if ((pass == 0 && (current.isEmpty()
                        || !ItemStack.isSameItemSameComponents(current, pending)))
                        || (pass == 1 && !current.isEmpty())) continue;
                int capacity = inventory.getMaxStackSize(pending) - current.getCount();
                int amount = Math.min(pending.getCount(), Math.max(0, capacity));
                if (amount == 0) continue;
                before.putIfAbsent(slot, inventory.getItem(slot).copy());
                after.put(slot, pending.copyWithCount(current.getCount() + amount));
                pending.shrink(amount);
            }
        }
        return pending.isEmpty();
    }
}
