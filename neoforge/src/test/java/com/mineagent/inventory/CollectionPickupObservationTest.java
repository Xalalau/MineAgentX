package com.mineagent.inventory;

import com.mineagent.engine.task.CollectionPickupObservation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionPickupObservationTest {
    @BeforeAll static void bootstrap() throws Exception { CollectionPickupTaskTest.bootstrap(); }

    private static ItemEntity target() {
        var target = new ItemEntity(EntityType.ITEM, null);
        target.setItem(new ItemStack(Items.STONE, 5));
        return target;
    }

    @Test void postReceiptCountsOnlyObservedMatchingDelivery() {
        var target = target();
        var inventory = new SimpleContainer(1);
        var observation = new CollectionPickupObservation(target, inventory);
        var before = target.getItem().copy();
        target.getItem().shrink(2);
        inventory.setItem(0, new ItemStack(Items.STONE, 2));
        observation.record(new ItemEntityPickupEvent.Post(null, target, before));
        assertEquals(2, observation.delivered());
    }

    @Test void anotherEntityReceiptCannotAttributeAnUnrelatedGrant() {
        var target = target();
        var other = target();
        other.setItem(ItemStack.EMPTY);
        var inventory = new SimpleContainer(1);
        var observation = new CollectionPickupObservation(target, inventory);
        inventory.setItem(0, new ItemStack(Items.STONE, 5));
        observation.record(new ItemEntityPickupEvent.Post(null, other, new ItemStack(Items.STONE, 5)));
        assertEquals(0, observation.delivered());
    }

    @Test void changedEntityComponentsCannotProduceAMatchingReceipt() {
        for (boolean changeBefore : new boolean[] {true, false}) {
            var target = target();
            var inventory = new SimpleContainer(1);
            var observation = new CollectionPickupObservation(target, inventory);
            var before = target.getItem().copy();
            target.getItem().shrink(2);
            (changeBefore ? before : target.getItem()).set(DataComponents.CUSTOM_NAME,
                    Component.literal("different components"));
            inventory.setItem(0, new ItemStack(Items.STONE, 2));
            observation.record(new ItemEntityPickupEvent.Post(null, target, before));
            assertEquals(0, observation.delivered());
        }
    }

    @Test void unrelatedGrantWithoutTargetReceiptCannotCountAsPickup() {
        var target = target();
        var inventory = new SimpleContainer(1);
        var observation = new CollectionPickupObservation(target, inventory);
        target.getItem().shrink(5);
        inventory.setItem(0, new ItemStack(Items.STONE, 5));
        assertEquals(0, observation.delivered(), "Neither disappearance nor gain is a pickup receipt");
    }

    @Test void differentComponentInventoryGainCannotCorroborateReceipt() {
        var target = target();
        target.getItem().set(DataComponents.CUSTOM_NAME, Component.literal("original"));
        var inventory = new SimpleContainer(1);
        var observation = new CollectionPickupObservation(target, inventory);
        var before = target.getItem().copy();
        target.getItem().shrink(2);
        inventory.setItem(0, new ItemStack(Items.STONE, 2));
        observation.record(new ItemEntityPickupEvent.Post(null, target, before));
        assertEquals(0, observation.delivered());
    }

    @Test void unrelatedMatchingGrantCannotInflateTargetReceipt() {
        var target = target();
        var inventory = new SimpleContainer(new ItemStack(Items.STONE, 7));
        var observation = new CollectionPickupObservation(target, inventory);
        var before = target.getItem().copy();
        target.getItem().shrink(2);
        inventory.getItem(0).grow(12);
        observation.record(new ItemEntityPickupEvent.Post(null, target, before));
        assertEquals(2, observation.delivered());
    }

    @Test void fullPickupReceiptSurvivesVanillaDiscardAndStackCountReset() {
        var target = target();
        var inventory = new SimpleContainer(1);
        var observation = new CollectionPickupObservation(target, inventory);
        var before = target.getItem().copy();
        // The mapped ItemEntity posts while empty, then discards and restores
        // the delivered count to the removed entity's stack for notifications.
        target.getItem().setCount(0);
        inventory.setItem(0, before.copy());
        observation.record(new ItemEntityPickupEvent.Post(null, target, before));
        target.discard();
        target.getItem().setCount(5);
        assertEquals(5, observation.delivered());
    }

    @Test void receiptWithoutFinalMatchingGainCannotClaimDelivery() {
        var target = target();
        var inventory = new SimpleContainer(1);
        var observation = new CollectionPickupObservation(target, inventory);
        var before = target.getItem().copy();
        target.getItem().shrink(2);
        observation.record(new ItemEntityPickupEvent.Post(null, target, before));
        assertEquals(0, observation.delivered());
        inventory.setItem(0, new ItemStack(Items.STONE, 1));
        assertEquals(1, observation.delivered());
    }
}
