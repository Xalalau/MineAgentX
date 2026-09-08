package com.mineagent.engine.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceReservationLedgerTest {
    @Test
    void exclusiveResourceCannotBeDoubleBooked() {
        ResourceReservationLedger ledger = new ResourceReservationLedger();
        assertTrue(ledger.reserve("a", "station:minecraft:crafting_table", "craft", 0, 100, true));
        assertFalse(ledger.reserve("b", "station:minecraft:crafting_table", "craft", 1, 100, true));
        ledger.expire(200);
        assertTrue(ledger.reserve("b", "station:minecraft:crafting_table", "craft", 200, 100, true));
    }


    @Test
    void exclusiveAndSharedReservationsConflictInBothDirections() {
        ResourceReservationLedger first = new ResourceReservationLedger();
        assertTrue(first.reserve("shared", "station:a", "observe", 0, 100, false));
        assertFalse(first.reserve("exclusive", "station:a", "use", 1, 100, true));

        ResourceReservationLedger second = new ResourceReservationLedger();
        assertTrue(second.reserve("exclusive", "station:a", "use", 0, 100, true));
        assertFalse(second.reserve("shared", "station:a", "observe", 1, 100, false));
    }

    @Test
    void liveReservationsDoNotSurviveRestart() {
        ResourceReservationLedger source = new ResourceReservationLedger();
        source.reserve("a", "output:minecraft:bed", "craft", 10, 100, true);
        ResourceReservationLedger restored = new ResourceReservationLedger();
        restored.importState(source.exportState());
        assertFalse(restored.isReserved("output:minecraft:bed", 10));
    }
}
