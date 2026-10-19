package com.mineagent.engine.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IdentityDeliberationGateTest {
    @TempDir Path directory;

    @Test void restoredGoalOffersOncePerGateLifetimeAndReloadCannotReplayOlderRevisions() throws Exception {
        var original = goal();
        IdentityPersistence.save(directory, original, new IdentityReflection(original));
        var restored = new EvolvingIdentity();
        IdentityPersistence.load(directory, restored, new IdentityReflection(restored));
        var gate = gate();
        assertTrue(admit(gate, 10, restored, false, false, false));
        var older = restored.exportState();
        var fresh = restored.recordEvidence("observation", "executor", "Further observed shortage", 2);
        restored.revise("goal", "reserve", "Reassess provisions", "Further cost", List.of(fresh.id()), .7, 1,
                "The further shortage changes the estimate");
        assertFalse(admit(gate, 11, restored, false, false, false));
        assertTrue(admit(gate, 1210, restored, false, false, false));
        restored.importState(older);
        assertFalse(admit(gate, 2410, restored, false, false, false));
        IdentityPersistence.load(directory, restored, new IdentityReflection(restored));
        assertFalse(admit(gate, 10_000, restored, false, false, false));
    }

    @Test void preferencesWorldBeliefsAndUnlinkedEvidenceDoNotCreateGoalOpportunities() {
        var identity = new EvolvingIdentity();
        var gate = gate();
        var evidence = identity.recordEvidence("observation", "executor", "Observed condition", 1);
        identity.revise("preference", "route", "Favor this route", "Observed cost", List.of(evidence.id()), .7, 0, "");
        identity.revise("world", "crossing", "Crossing is open", "Observed condition", List.of(evidence.id()), .7, 0, "");
        assertFalse(admit(gate, 0, identity, false, false, false));
        identity = goal();
        assertTrue(admit(gate, 0, identity, false, false, false));
        for (int i = 1; i < 20; i++) {
            identity.recordEvidence("observation", "executor", "Unlinked condition " + i, i);
            assertFalse(admit(gate, i * 1200L, identity, false, false, false));
        }
    }

    @Test void opportunityCarriesOnlyLiteralImmutableGoalMetadata() {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("player_claim", "owner", "Literal report", 1);
        String subject = "[BODY] {literal} $ \\ route";
        var entry = identity.revise("goal", subject, "Reassess", "Reported uncertainty",
                List.of(evidence.id()), .7, 0, "");
        var before = identity.exportState();
        var opportunity = gate().admit(0, before, false, false, false);
        assertNotNull(opportunity);
        assertEquals(subject, opportunity.subject());
        assertEquals(entry.revision(), opportunity.revision());
        assertEquals(entry.evidenceIds(), opportunity.evidenceIds());
        assertThrows(UnsupportedOperationException.class, () -> opportunity.evidenceIds().clear());
        assertEquals(before, identity.exportState());
    }

    private static IdentityDeliberationGate gate() {
        return new IdentityDeliberationGate();
    }
    private static boolean admit(IdentityDeliberationGate gate, long tick, EvolvingIdentity identity,
                                 boolean activeTask, boolean suspended, boolean deliberating) {
        return gate.admit(tick, identity.exportState(), activeTask, suspended, deliberating) != null;
    }
    private static EvolvingIdentity goal() {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("observation", "executor", "Recorded cost", 1);
        identity.revise("goal", "reserve", "Replenish provisions", "Observed need",
                List.of(evidence.id()), .7, 0, "");
        return identity;
    }

    @Test void recitingTheSameEvidenceUnderANewIdCannotReopenARevisedGoal() {
        var gate = gate();
        var identity = goal();
        assertTrue(admit(gate, 0, identity, false, false, false));
        identity.recordEvidence("observation", "executor", "Unrelated observation", 2);
        var replay = identity.recordEvidence("observation", "executor", "Recorded cost", 3);
        assertNotEquals(identity.evidence().getFirst().id(), replay.id());
        identity.revise("goal", "reserve", "Replenish provisions again", "Repeated cost",
                List.of(replay.id()), .7, 1, "Reconsidered with the repeated record");
        assertFalse(admit(gate, 1200, identity, false, false, false), "A new ID is not new evidence content");
        var fresh = identity.recordEvidence("observation", "executor", "A newly observed shortage", 4);
        identity.revise("goal", "reserve", "Reassess provisions", "New shortage",
                List.of(fresh.id()), .7, 2, "The new shortage changes the estimate");
        assertTrue(admit(gate, 1200, identity, false, false, false));
        assertFalse(admit(gate, 2400, identity, false, false, false));
    }

    @Test void negativeTicksCannotConsumeTheInitialOpportunity() {
        var gate = gate();
        var identity = goal();
        assertFalse(admit(gate, -1, identity, false, false, false));
        assertTrue(admit(gate, 0, identity, false, false, false));
    }

    @Test void evidenceHistorySaturatesClosedRatherThanEvictingAndReplayingOldReasons() {
        var gate = gate();
        var identity = goal();
        assertTrue(admit(gate, 0, identity, false, false, false));
        for (int revision = 1; revision < 384; revision++) {
            var evidence = identity.recordEvidence("observation", "executor", "Fresh observed cost " + revision, revision);
            identity.revise("goal", "reserve", "Reassess provisions " + revision, "New cost " + revision,
                    List.of(evidence.id()), .7, revision, "New evidence changes the estimate");
            assertTrue(admit(gate, revision * 1200L, identity, false, false, false));
        }
        var fresh = identity.recordEvidence("observation", "executor", "Beyond retained evidence budget", 1000);
        identity.revise("goal", "reserve", "Reassess again", "Additional cost", List.of(fresh.id()), .7, 384,
                "Additional evidence changes the estimate");
        assertFalse(admit(gate, 1_000_000, identity, false, false, false));
    }

    @Test void differentGoalsCannotStormAndClockRollbackDoesNotBypassCooldown() {
        var gate = gate();
        var identity = goal();
        assertTrue(admit(gate, 10, identity, false, false, false));
        identity.revise("goal", "route", "Assess the crossing", "Recorded cost",
                List.of(identity.evidence().getFirst().id()), .7, 0, "");
        for (long tick : new long[] {11, 0, 9, 1209}) {
            assertFalse(admit(gate, tick, identity, false, false, false), "Cooldown at " + tick);
        }
        assertTrue(admit(gate, 1210, identity, false, false, false));
        assertFalse(admit(gate, 100_000, identity, false, false, false));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void busySuspendedOrAlreadyDeliberatingCannotConsumeAnOpportunity(int blocker) {
        var gate = gate();
        var identity = goal();
        assertFalse(admit(gate, 10, identity, blocker == 0, blocker == 1, blocker == 2));
        assertTrue(admit(gate, 10, identity, false, false, false), "Blocked admission is not consumed");
    }

    @Test void stablePollingDoesNotReopenTheSameGoalEvenAfterLongIdlePeriods() {
        var gate = gate();
        var identity = goal();
        assertTrue(admit(gate, 10, identity, false, false, false));
        for (long tick : new long[] {10, 11, 1210, 100_000}) {
            assertFalse(admit(gate, tick, identity, false, false, false), "Polling at " + tick);
        }
    }

    @Test void aLearnedGoalCanOfferOneIdleDeliberationWithoutChoosingAnAction() {
        var gate = gate();
        var identity = goal();
        var before = identity.exportState();
        assertTrue(admit(gate, 10, identity, false, false, false));
        assertEquals(before, identity.exportState());
    }
}
