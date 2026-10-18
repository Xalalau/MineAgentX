package com.mineagent.engine.task;

import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * A transient observation of one synchronous touch, never a second inventory.
 * Inventory gain alone or an entity's disappearance is not a pickup receipt.
 */
public final class CollectionPickupObservation {
    private final ItemEntity target;
    private final Container inventory;
    private final ItemStack original;
    private final int baseline;
    private int receipt;

    public CollectionPickupObservation(ItemEntity target, Container inventory) {
        this.target = target;
        this.inventory = inventory;
        this.original = target.getItem().copy();
        this.baseline = matchingCount();
    }

    private int matchingCount() {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(original, stack)) count += stack.getCount();
        }
        return count;
    }

    /**
     * Called only for a post-event from the player's synchronous touch. Capture
     * the reduction now: ItemEntity resets the removed stack's count after Post.
     */
    public void record(ItemEntityPickupEvent.Post event) {
        if (event.getItemEntity() != target) return;
        ItemStack before = event.getOriginalStack();
        ItemStack after = event.getCurrentStack();
        if (!ItemStack.matches(original, before)
                || (!after.isEmpty() && !ItemStack.isSameItemSameComponents(original, after))) return;
        receipt = before.getCount() - after.getCount();
    }

    /** Corroborate the post-event with matching inventory gain. */
    public int delivered() {
        return Math.min(Math.max(0, matchingCount() - baseline), Math.max(0, receipt));
    }
}
