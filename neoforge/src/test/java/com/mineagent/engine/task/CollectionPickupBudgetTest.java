package com.mineagent.engine.task;

import com.mineagent.api.execution.ExecutionToken;
import com.mineagent.tools.inventory.CollectItemsTool;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Pure executor-budget regressions; real inventory and lifecycle RED/GREEN use loaded GameTests. */
final class CollectionPickupBudgetTest {
    private static CollectItemsTask task() {
        return new CollectItemsTask(null,
                new CollectItemsTool.CollectItemsTaskRecord("budget", "minecraft:stone", 8, 5));
    }

    @Test void aReservedAttemptConsumesTheRemainingBudgetBeforeItsReceipt() {
        var task = task();
        assertTrue(task.reserveCollisionPickup(5));
        assertFalse(task.reserveCollisionPickup(1));
        assertEquals(0, task.snapshot().completedUnits(), "Reservation is not a receipt");
    }

    @Test void missingReceiptNeverReopensTheReservedBudget() {
        var task = task();
        assertTrue(task.reserveCollisionPickup(5));
        task.settleCollisionPickup(5, 0);
        assertFalse(task.reserveCollisionPickup(1));
        assertEquals(0, task.snapshot().completedUnits());
    }

    @Test void corroboratedPartialReceiptReleasesOnlyTheUnusedReservation() {
        var task = task();
        assertTrue(task.reserveCollisionPickup(5));
        task.settleCollisionPickup(5, 2);
        assertEquals(2, task.snapshot().completedUnits());
        assertTrue(task.reserveCollisionPickup(3));
        assertFalse(task.reserveCollisionPickup(1));
        task.settleCollisionPickup(3, 3);
        assertEquals(5, task.snapshot().completedUnits());
        assertFalse(task.reserveCollisionPickup(1));
    }

    @Test void oversizedAndNonpositiveAttemptsDoNotBurnBudget() {
        var task = task();
        assertFalse(task.reserveCollisionPickup(6));
        assertFalse(task.reserveCollisionPickup(0));
        assertFalse(task.reserveCollisionPickup(-1));
        assertTrue(task.reserveCollisionPickup(5));
    }

    @Test void revocationRemainsDifferentFromAnUnboundLegacyInvocation() {
        var active = new AtomicBoolean(true);
        CollectItemsTool.CollectItemsTaskRecord record;
        try (var scope = ExecutionToken.bindRetainedAuthority(active::get)) {
            record = new CollectItemsTool.CollectItemsTaskRecord("same-id", "minecraft:stone", 8, 5);
        }
        var retained = new CollectItemsTask(null, record);
        assertTrue(record.requiresRetainedAuthority());
        assertTrue(retained.capsCollisions());
        active.set(false);
        assertFalse(retained.capsCollisions());
        assertFalse(retained.pickupAuthorityActive());
        assertTrue(record.requiresRetainedAuthority());
        assertFalse(task().capsCollisions());
        assertTrue(task().pickupAuthorityActive(), "Legacy invocation never acquires retained authority");
    }
}
