package com.mineagent.tools.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Server-thread operations on the live menu slots, not a separate inventory. */
public final class InventorySlotTransactions {
    private InventorySlotTransactions() {}

    public record Outcome(int moved, boolean swap, boolean verified, String code) {
        public com.google.gson.JsonObject result(int requested) {
            var result = new com.google.gson.JsonObject();
            result.addProperty("success", moved > 0 && verified);
            result.addProperty("count", moved);
            result.addProperty("requested", requested);
            result.addProperty("remaining", Math.max(0, requested - moved));
            result.addProperty("complete", moved > 0 && moved == requested && verified);
            result.addProperty("postcondition_verified", verified);
            if (code != null) result.addProperty("code", code);
            return result;
        }
    }

    public static com.google.gson.JsonObject dropResult(String itemId, int requested, int dropped,
                                                         int carriedBefore, int carriedAfter,
                                                         boolean deliveryVerified, boolean deliveryRefused) {
        boolean verified = deliveryVerified && carriedAfter == carriedBefore - dropped;
        var result = new Outcome(dropped, false, verified, null).result(requested);
        result.addProperty("item", itemId);
        result.addProperty("dropped", dropped);
        result.addProperty("carried_before", carriedBefore);
        result.addProperty("carried_after", carriedAfter);
        if (deliveryRefused) result.addProperty("stopped_reason", "delivery_refused");
        if (dropped == 0) result.addProperty("error", InventoryMessages.t("No matching items could be dropped."));
        return result;
    }

    /** Delivery must acknowledge actual world membership, not merely entity allocation. */
    public static int drop(Slot source, int requested, Player player,
                           java.util.function.Predicate<ItemStack> delivery) {
        ItemStack before = source.getItem().copy();
        if (requested <= 0 || before.isEmpty() || !source.isActive() || !source.mayPickup(player)) return 0;
        ItemStack taken = source.tryRemove(Math.min(requested, before.getCount()),
                Math.min(requested, before.getCount()), player).orElse(ItemStack.EMPTY);
        if (taken.isEmpty()) return 0;
        ItemStack receipt = taken.copy();
        if (!delivery.test(taken)) {
            source.setByPlayer(before);
            return 0;
        }
        source.onTake(player, receipt);
        return receipt.getCount();
    }

    public static Outcome move(Slot source, Slot destination, int requested, Player player) {
        ItemStack from = source.getItem().copy();
        ItemStack to = destination.getItem().copy();
        if (source == destination || (source.container == destination.container
                && source.getContainerSlot() == destination.getContainerSlot())) {
            return new Outcome(0, false, true, "same_slot");
        }
        if (requested <= 0 || from.isEmpty() || !source.isActive()
                || !destination.isActive() || !source.mayPickup(player)
                || !destination.mayPlace(from)) {
            return new Outcome(0, false, true, "slot_refused");
        }
        if (!to.isEmpty() && !ItemStack.isSameItemSameComponents(from, to)) {
            if (requested < from.getCount() || !destination.mayPickup(player)
                    || !source.mayPlace(to)
                    || from.getCount() > destination.getMaxStackSize(from)
                    || to.getCount() > source.getMaxStackSize(to)) {
                return new Outcome(0, false, true, "swap_refused");
            }
            ItemStack extractedSource = source.tryRemove(from.getCount(), from.getCount(), player)
                    .orElse(ItemStack.EMPTY);
            if (!ItemStack.matches(extractedSource, from)) {
                source.setByPlayer(from.copy());
                return new Outcome(0, false, true, "swap_extraction_refused");
            }
            ItemStack extractedDestination = destination.tryRemove(to.getCount(), to.getCount(), player)
                    .orElse(ItemStack.EMPTY);
            if (!ItemStack.matches(extractedDestination, to)) {
                source.setByPlayer(from.copy());
                destination.setByPlayer(to.copy());
                return new Outcome(0, false, true, "swap_extraction_refused");
            }
            source.setByPlayer(extractedDestination);
            destination.setByPlayer(extractedSource);
            source.onTake(player, from.copy());
            destination.onTake(player, to.copy());
            boolean verified = ItemStack.matches(to, source.getItem())
                    && ItemStack.matches(from, destination.getItem());
            return new Outcome(from.getCount(), true, verified, null);
        }
        int amount = Math.min(Math.min(requested, from.getCount()),
                Math.max(0, destination.getMaxStackSize(from) - to.getCount()));
        if (amount == 0) return new Outcome(0, false, true, "destination_full");
        // Defer onTake until insertion is known. Result slots consume inputs in
        // onTake; charging them for rejected items would lose recipe ingredients.
        boolean allowsPartial = source.allowModification(player);
        ItemStack taken = source.tryRemove(amount, amount, player).orElse(ItemStack.EMPTY);
        int extracted = taken.getCount();
        ItemStack acceptedIdentity = taken.copy();
        ItemStack rejected = destination.safeInsert(taken, extracted);
        int moved = extracted - rejected.getCount();
        if (!rejected.isEmpty() && !allowsPartial) {
            // A result-like slot is one indivisible recipe charge. Restoring
            // only its rejected tail and then invoking onTake can erase that
            // tail when the menu recomputes its next recipe result.
            destination.setByPlayer(to.copy());
            source.setByPlayer(from.copy());
            return new Outcome(0, false, true, "partial_result_refused");
        }
        if (!rejected.isEmpty()) {
            // No onTake has run yet: restore the unaccepted extraction to its
            // original backing slot, including result slots that forbid placing.
            ItemStack restored = source.getItem().copy();
            if (restored.isEmpty()) restored = rejected;
            else restored.grow(rejected.getCount());
            source.setByPlayer(restored);
        }
        if (moved > 0) source.onTake(player, acceptedIdentity.copyWithCount(moved));
        boolean verified = source.getItem().getCount() == from.getCount() - moved
                && destination.getItem().getCount() == to.getCount() + moved
                && (source.getItem().isEmpty() || ItemStack.isSameItemSameComponents(from, source.getItem()))
                && (moved == 0 || ItemStack.isSameItemSameComponents(from, destination.getItem()));
        return new Outcome(moved, false, verified, moved > 0 ? null : "source_refused");
    }
}
