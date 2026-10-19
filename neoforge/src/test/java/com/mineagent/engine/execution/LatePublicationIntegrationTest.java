package com.mineagent.engine.execution;

import com.mineagent.engine.identity.EvolvingIdentity;
import com.mineagent.engine.world.ActionTransactionLedger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real identity and transaction writers, not a duplicate test state machine. */
final class LatePublicationIntegrationTest {
    @ParameterizedTest @ValueSource(strings = {"deadline", "generation", "owner_cancel"})
    void obsoletePhysicalReceiptCannotPublishRealLedgerOrIdentityLearning(String invalidation) {
        var identity = new EvolvingIdentity();
        var ledger = new ActionTransactionLedger();
        ledger.begin("call", "craft", "inventory delivery", null, 1);
        var now = new AtomicLong(1);
        var generation = new AtomicLong(1);
        var server = new ArrayDeque<Runnable>();
        var owner = new ExecutionOwner(now::get, server::add);
        var lease = owner.open("call", () -> generation.get() == 1, 10,
                () -> ledger.finish("call", ActionTransactionLedger.Status.UNVERIFIED,
                        "execution revoked; physical effects need fresh observation", null, 2));
        var callback = new AtomicReference<Consumer<String>>();
        var receipts = new ArrayList<String>();
        lease.call().dispatch(() -> { }, callback::set, raw -> {
            ledger.finishObserved("call", true, raw, null, 2);
            identity.recordEvidence("observation", "tool:craft", raw, 2);
        }, receipts::add, failure -> "thrown", server::add, lease::requestCancellation, "unavailable");
        switch (invalidation) {
            case "deadline" -> now.set(10);
            case "generation" -> owner.invalidate(generation::incrementAndGet);
            case "owner_cancel" -> owner.revokeAll("cancelled", generation::incrementAndGet);
            default -> throw new AssertionError(invalidation);
        }
        String receipt = "{\"success\":true,\"crafted_batches\":1,\"postcondition_verified\":true}";
        callback.get().accept(receipt);
        while (!server.isEmpty()) server.remove().run();
        assertTrue(identity.evidence().isEmpty(), "a stale tool callback cannot create identity evidence");
        assertEquals(ActionTransactionLedger.Status.UNVERIFIED, ledger.get("call").status());
        assertEquals(java.util.List.of(receipt), receipts, "real physical effects remain available as separate observations");
        assertEquals(ExecutionProtocol.State.EXPIRED, lease.call().terminal().state());
    }
}
