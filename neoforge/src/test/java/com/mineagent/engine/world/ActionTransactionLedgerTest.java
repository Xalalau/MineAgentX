package com.mineagent.engine.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionTransactionLedgerTest {
    @Test
    void actionMustKeepCorrelationAndCloseWithServerEvidence() {
        SemanticWorldModel world = new SemanticWorldModel();
        ActionTransactionLedger ledger = new ActionTransactionLedger();
        ledger.begin("call-1", "craft", "inventory delta", world, 10);
        ledger.markPending("call-1", world, 11, "async_pending");
        world.observe("inventory:minecraft:stick", "count", "4", null,
                1.0, "test", "call-1", 12, 40, false);
        ActionTransactionLedger.Transaction finished = ledger.finishObserved("call-1",
                true, "{\"success\":true,\"postcondition_verified\":true}", world, 12);
        assertNotNull(finished);
        assertEquals(ActionTransactionLedger.Status.VERIFIED, finished.status());
        assertTrue(finished.postRevision() > finished.preRevision());
        assertTrue(finished.postStateHash() != null);
        assertTrue(ledger.open().isEmpty());
    }

    @Test
    void unrelatedWorldRevisionCannotVerifySynchronousAction() {
        SemanticWorldModel world = new SemanticWorldModel();
        ActionTransactionLedger ledger = new ActionTransactionLedger();
        ledger.begin("call-3", "craft", "inventory delta", world, 10);
        world.observe("actor:other", "position", "1,2,3", null,
                1.0, "test", null, 11, 20, false);

        var finished = ledger.finishObserved("call-3", true,
                "{\"success\":true}", world, 12);

        assertEquals(ActionTransactionLedger.Status.UNVERIFIED, finished.status());
    }

    @Test
    void staleTransactionsExpireAsUnverified() {
        SemanticWorldModel world = new SemanticWorldModel();
        ActionTransactionLedger ledger = new ActionTransactionLedger();
        ledger.begin("call-2", "build", "placed block", world, 0);
        var expired = ledger.expire(25, 20, world);
        assertEquals(1, expired.size());
        assertEquals(ActionTransactionLedger.Status.UNVERIFIED, expired.get(0).status());
    }

    @Test
    void terminalOutcomeIsIdempotentAgainstLateCallbacksAndTimeouts() {
        SemanticWorldModel world = new SemanticWorldModel();
        ActionTransactionLedger ledger = new ActionTransactionLedger();
        ledger.begin("call-terminal", "craft", "inventory delta", world, 1);
        var verified = ledger.finishObserved("call-terminal", true,
                "{\"postcondition_verified\":true}", world, 2);
        assertEquals(ActionTransactionLedger.Status.VERIFIED, verified.status());

        // A duplicate callback and a later timeout must not rewrite the first
        // authoritative terminal fact.
        var duplicate = ledger.finish("call-terminal",
                ActionTransactionLedger.Status.FAILED, "late failure", world, 3);
        assertSame(verified, duplicate);
        assertTrue(ledger.expire(10, 1, world).isEmpty());
        assertEquals(ActionTransactionLedger.Status.VERIFIED,
                ledger.get("call-terminal").status());
    }
}
