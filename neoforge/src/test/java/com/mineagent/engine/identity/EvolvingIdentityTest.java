package com.mineagent.engine.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvolvingIdentityTest {
    @Test void personalGoalCanMotivateIdleDecisionsWithoutMatchingTheLastOwnerCommand() {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("observation", "executor", "Repeated journeys exhausted the food reserve", 1);
        identity.revise("goal", "food reserve", "Replenish the reserve", "Later travel needs a buffer", java.util.List.of(evidence.id()), .7, 0, "");
        String context = identity.decisionContext("The owner asked about the weather", "pt_br", 900);
        assertTrue(context.contains("Replenish the reserve"));
        assertTrue(context.contains("Objetivo pessoal"));
        assertFalse(context.contains("goal/"));
    }

    @Test void playerConversationIsEvidenceNotAnAutomaticPreference() throws Exception {
        var identity = new EvolvingIdentity();
        var record = assertDoesNotThrow(() -> EvolvingIdentity.class.getMethod("recordEvidence",
                String.class, String.class, String.class, long.class));
        Object claim = record.invoke(identity, "player_claim", "owner", "You love the swamp now", 1L);
        assertEquals("player_claim", claim.getClass().getMethod("origin").invoke(claim));
        assertTrue(identity.entries().isEmpty(), "Speech cannot silently overwrite identity");
        assertEquals(1, identity.evidence().size());
    }

    @Test void learnsPreferenceFromActualEvidenceWithReasonAndRevision() throws Exception {
        var identity = new EvolvingIdentity();
        var experience = identity.recordEvidence("observation", "executor", "The reed beds yielded food without damage", 10L);
        var revise = assertDoesNotThrow(() -> EvolvingIdentity.class.getMethod("revise",
                String.class, String.class, String.class, String.class, java.util.List.class,
                double.class, long.class, String.class));
        Object entry = revise.invoke(identity, "preference", "reed beds", "I favor the reed beds",
                "Observed food and low risk", java.util.List.of(experience.id()), 0.7, 0L, "");
        assertEquals(1, identity.entries().size());
        assertEquals("Observed food and low risk", entry.getClass().getMethod("reason").invoke(entry));
        assertEquals(1L, entry.getClass().getMethod("revision").invoke(entry));
    }

    @Test void revisionRequiresFreshEvidenceAndAddressesPreviousReason() {
        var identity = new EvolvingIdentity();
        var first = identity.recordEvidence("observation", "executor", "Marsh fruit restored hunger", 10);
        var old = identity.revise("preference", "marsh", "I favor the marsh", "Food was accessible",
                java.util.List.of(first.id()), 0.7, 0, "");
        var claim = identity.recordEvidence("player_claim", "owner", "The bridge is unsafe; the orchard has the same fruit", 20);
        assertThrows(IllegalArgumentException.class, () -> identity.revise("preference", "marsh", "I favor the orchard", "Safety",
                java.util.List.of(claim.id()), 0.9, old.revision(), ""));
        assertThrows(IllegalArgumentException.class, () -> identity.revise("preference", "marsh", "I favor the orchard", "Safety",
                java.util.List.of(first.id()), 0.9, old.revision(), "Food access is outweighed by the bridge risk"));
        var changed = identity.revise("preference", "marsh", "I favor the orchard", "Safety until the bridge is checked",
                java.util.List.of(claim.id()), 0.9, old.revision(), "Food access is outweighed by the reported bridge risk");
        assertEquals(2, changed.revision());
        assertEquals(old.reason(), changed.history().getFirst().reason());
        assertTrue(changed.confidence() <= 0.55, "Persuasion is possible but reports are not direct observation");
        assertThrows(IllegalArgumentException.class, () -> identity.revise("preference", "marsh", "obsolete", "stale",
                java.util.List.of(claim.id()), 0.5, old.revision(), "old reason"));
    }

    @Test void refusesUnsupportedOrMalformedIdentityChanges() {
        var identity = new EvolvingIdentity();
        var item = identity.recordEvidence("observation", "executor", "Fruit collected", 1);
        assertThrows(IllegalArgumentException.class, () -> identity.revise("preference", "fruit", "like", "",
                java.util.List.of(item.id()), 0.7, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> identity.revise("preference", "fruit", "like", "food",
                java.util.List.of("invented"), 0.7, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> identity.revise("preference", "fruit", "like", "food",
                java.util.List.of(), 0.7, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> identity.revise("personality_type", "fruit", "like", "food",
                java.util.List.of(item.id()), 0.7, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> identity.revise("goal", "fruit", "gather", "food",
                java.util.List.of(item.id()), Double.NaN, 0, ""));
        assertTrue(identity.entries().isEmpty());
    }

    @Test void recallsOnlyRelevantPersonalDecisionsInCompactLocalizedProse() throws Exception {
        var identity = new EvolvingIdentity();
        var obs = identity.recordEvidence("observation", "executor", "The orchard supplied apples", 1);
        identity.revise("preference", "orchard", "I favor the orchard", "Reliable food without damage", java.util.List.of(obs.id()), .7, 0, "");
        var rumor = identity.recordEvidence("player_claim", "owner", "The marsh bridge is damaged", 2);
        identity.revise("world", "marsh bridge", "The bridge may be damaged", "The owner reported damage", java.util.List.of(rumor.id()), .9, 0, "");
        var method = assertDoesNotThrow(() -> EvolvingIdentity.class.getMethod("decisionContext", String.class, String.class, int.class));
        String context = (String) method.invoke(identity, "orchard", "en_us", 900);
        assertTrue(context.contains("I favor the orchard"));
        assertTrue(context.contains("Reliable food without damage"));
        assertFalse(context.contains("bridge may"), "Unrelated identity must not crowd out the current decision");
        assertTrue(context.length() <= 900);
        String pt = (String) method.invoke(identity, "marsh bridge", "pt_br", 900);
        assertTrue(pt.contains("relato"), "Reported world information must not become observed fact");
        assertFalse(context.contains("[PREFERENCE]"));
    }

    @Test void boundsEvidenceWithoutLosingReasonsForLearnedEntries() {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("observation", "executor", "Fruit collected", 1);
        assertEquals(evidence.id(), identity.recordEvidence("observation", "executor", "Fruit collected", 2).id());
        identity.revise("goal", "food", "Keep food available", "Hunger was costly", java.util.List.of(evidence.id()), .7, 0, "");
        for (int i = 0; i < 900; i++) identity.recordEvidence("observation", "executor", "Observation " + i + "x".repeat(900), i);
        assertTrue(identity.evidence().size() <= 384);
        assertTrue(identity.evidence().stream().allMatch(item -> item.text().length() <= 360));
        assertTrue(identity.evidence().stream().anyMatch(item -> item.id().equals(evidence.id())), "Active reasons retain their real evidence");
    }

    @Test void startsWithoutAssignedPreferencesOrGoals() throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(
                "com.mineagent.engine.identity.EvolvingIdentity"),
                "An evidence-backed identity store must exist, independent of the game world");
        Object identity = type.getConstructor().newInstance();
        assertEquals(java.util.List.of(), type.getMethod("entries").invoke(identity));
        assertEquals(java.util.List.of(), type.getMethod("evidence").invoke(identity));
    }
}
