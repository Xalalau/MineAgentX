package com.mineagent.engine.execution;

import com.mineagent.api.execution.ExecutionToken;
import com.mineagent.tools.inventory.InventoryCraftTransactions;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Real vanilla inventory writes under the exact execution scope used by both AgentLoop paths. */
final class CraftRevocationIntegrationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownerRevocationDuringCommittedBatchPreservesItsActualInventoryReceipt(boolean skill) {
        runCraft(skill, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deadlineDuringCommittedBatchPreservesItsActualInventoryReceipt(boolean skill) {
        runCraft(skill, true);
    }

    private static void runCraft(boolean skill, boolean deadline) {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, new ItemStack(Items.OAK_LOG, 3));
        AtomicLong now = new AtomicLong(1);
        var server = new ArrayDeque<Runnable>();
        var owner = new ExecutionOwner(now::get, server::add);
        var lease = owner.open("craft-call", () -> true, 10, null);
        var receipts = new ArrayList<String>();
        var learned = new ArrayList<String>();
        var committed = new AtomicInteger();
        Runnable dispatch = () -> lease.call().dispatch(() -> { }, reply -> {
            for (int batch = 0; batch < 3; batch++) {
                if (!ExecutionToken.active()) break;
                var outcome = InventoryCraftTransactions.craftAndNotify(inventory,
                        new int[]{0}, List.of(new ItemStack(Items.OAK_LOG)),
                        new ItemStack(Items.OAK_PLANKS, 4), List.of(ItemStack.EMPTY), () -> {
                            if (deadline) now.set(10);
                            else {
                                Thread reasoning = Thread.ofPlatform().start(() -> owner.revokeAll("revoked", () -> { }));
                                try { reasoning.join(2000); } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                                }
                                assertFalse(reasoning.isAlive());
                            }
                        });
                assertTrue(outcome.committed());
                committed.incrementAndGet();
            }
            reply.accept("{\"crafted_batches\":" + committed.get() + ",\"crafted_items\":"
                    + inventory.countItem(Items.OAK_PLANKS) + "}");
        }, learned::add, receipts::add, thrown -> { throw new AssertionError(thrown); }, server::add,
                lease::requestCancellation, "expired");
        if (skill) dispatch.run(); else { server.add(dispatch); server.remove().run(); }
        if (skill) lease.call().requireImmediate("expired");
        assertEquals(1, committed.get());
        assertEquals(2, inventory.countItem(Items.OAK_LOG));
        assertEquals(4, inventory.countItem(Items.OAK_PLANKS));
        assertEquals(List.of("{\"crafted_batches\":1,\"crafted_items\":4}"), receipts);
        assertTrue(learned.isEmpty(), "late physical observations must not enter learned action/identity outcomes");
        assertEquals(ExecutionProtocol.State.EXPIRED, lease.call().terminal().state());
    }
}
