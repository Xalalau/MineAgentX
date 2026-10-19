package com.mineagent.engine.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IdentityCommitmentRetrievalTest {
    @TempDir Path directory;

    @Test void restoredPreferenceRemainsAvailableWhenQueryLanguageChanges() throws Exception {
        var identity = new EvolvingIdentity();
        var observed = identity.recordEvidence("observation", "executor", "A travessia preservou os recursos", 1);
        identity.revise("preference", "trajeto", "Prefiro a margem arborizada [TEAM] {literal} \\ $",
                "Menor desgaste durante a expedição", List.of(observed.id()), .7, 0, "");
        var claim = identity.recordEvidence("player_claim", "owner", "A margem está interditada", 2);
        var revised = identity.revise("preference", "trajeto", "Prefiro o planalto [BODY] {literal} \\ $",
                "Acesso incerto na margem", List.of(claim.id()), .9, 1,
                "A antiga economia de recursos não justifica entrar sem permissão");
        for (int i = 0; i < 3; i++) {
            identity.revise("world", "travel " + i, "Travel route is open " + i, "Travel inspection",
                    List.of(observed.id()), .7, 0, "");
        }
        IdentityPersistence.save(directory, identity, new IdentityReflection(identity));
        var restored = new EvolvingIdentity();
        IdentityPersistence.load(directory, restored, new IdentityReflection(restored));
        assertEquals(identity.exportState(), restored.exportState());
        assertTrue(restored.decisionContext("trajeto", "pt_br", 1800).contains(revised.statement()));
        String en = restored.decisionContext("travel route inspection", "en_us", 1800);
        assertTrue(en.contains(revised.statement()), en);
        assertTrue(en.contains(revised.reason()));
        assertTrue(en.contains("player report, not verified; 0.55; r2"));
        assertFalse(en.contains("Prefiro a margem"), "Only the current revision belongs in compact context");
        assertEquals(3, en.lines().count());
        assertEquals(revised, restored.entries().getFirst(), "Retrieval must not translate or revise stored memory");
    }

    @Test void diversityUsesKindsAndAvailableLexicalRelevanceNotLanguageSpecificWords() {
        var identity = new EvolvingIdentity();
        var observation = identity.recordEvidence("observation", "executor", "Direct evidence", 1);
        for (int i = 0; i < 5; i++) {
            identity.revise("preference", "\u7121\u95a2\u4fc2" + i, "\u8a18\u61b6" + i, "\u7406\u7531" + i,
                    List.of(observation.id()), .7, 0, "");
            identity.revise("goal", "\u5225\u4ef6" + i, "\u76ee\u7684" + i, "\u6839\u62e0" + i,
                    List.of(observation.id()), .7, 0, "");
        }
        identity.revise("preference", "cobalt", "Keep the cobalt preference", "Cobalt evidence",
                List.of(observation.id()), .7, 0, "");
        identity.revise("goal", "cobalt", "Keep the cobalt commitment", "Cobalt evidence",
                List.of(observation.id()), .7, 0, "");
        for (int i = 0; i < 3; i++) identity.revise("world", "cobalt " + i,
                "Cobalt cobalt cobalt " + i, "Cobalt evidence", List.of(observation.id()), .7, 0, "");
        for (String language : List.of("en_us", "pt_br")) {
            String context = identity.decisionContext("cobalt", language, 1800);
            assertTrue(context.contains("Keep the cobalt preference"), context);
            assertTrue(context.contains("Keep the cobalt commitment"), context);
            assertTrue(context.contains("Cobalt cobalt cobalt"));
            assertFalse(context.contains("\u8a18\u61b6"));
            assertEquals(3, context.lines().count());
        }
    }

    @Test void unrelatedWorldBeliefsRemainExcludedEvenWithNonLexicalCommitmentFallback() {
        var identity = new EvolvingIdentity();
        var observed = identity.recordEvidence("observation", "executor", "Direct evidence", 1);
        identity.revise("preference", "\u7d4c\u8def", "\u68ee\u3092\u597d\u3080", "\u9759\u304b\u306a\u65c5", List.of(observed.id()), .7, 0, "");
        identity.revise("goal", "provisões", "Repor a reserva", "Expedição futura", List.of(observed.id()), .7, 0, "");
        identity.revise("world", "volcano", "Unrelated world fact", "Distant observation", List.of(observed.id()), .7, 0, "");
        for (String language : List.of("en_us", "pt_br")) {
            String context = identity.decisionContext("weather forecast", language, 1200);
            assertTrue(context.contains("\u68ee\u3092\u597d\u3080"));
            assertTrue(context.contains("Repor a reserva"));
            assertFalse(context.contains("Unrelated world fact"));
            assertEquals(2, context.lines().count());
        }
    }

    @Test void wholeRecordsRespectEveryCharacterBudgetWithoutMutatingMemory() {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("observation", "executor", "Direct evidence", 1);
        identity.revise("preference", "route", "Preserve [TEAM] {opaque} and $ literally",
                "Direct evidence", List.of(evidence.id()), .7, 0, "");
        var before = identity.exportState();
        for (String language : List.of("en_us", "pt_br")) {
            String full = identity.decisionContext("other", language, 900);
            assertFalse(full.isEmpty());
            for (int budget : new int[] {Integer.MIN_VALUE, -1, 0, 1, full.length() - 1}) {
                assertEquals("", identity.decisionContext("other", language, budget));
            }
            assertEquals(full, identity.decisionContext("other", language, full.length()));
            assertEquals(before, identity.exportState());
        }
    }

    @Test void emptyIdentityDoesNotInventCommitmentsAndBlankQueriesRemainBounded() {
        var identity = new EvolvingIdentity();
        assertEquals("", identity.decisionContext(null, "en_us", 900));
        var observed = identity.recordEvidence("observation", "executor", "Direct evidence", 1);
        for (int i = 0; i < 6; i++) identity.revise("world", "site " + i,
                "Observed site " + i, "Direct evidence", List.of(observed.id()), .7, 0, "");
        for (String query : new String[] {null, "", "  "}) {
            String context = identity.decisionContext(query, "en_us", 900);
            assertEquals(3, context.lines().count());
            assertTrue(context.length() <= 900);
        }
    }

    @Test void personalGoalSurvivesThreeLexicallyRelevantWorldBeliefs() {
        var identity = new EvolvingIdentity();
        var observed = identity.recordEvidence("observation", "executor", "The last expedition depleted supplies", 1);
        identity.revise("goal", "reserve", "Replenish provisions", "Future journeys need a buffer",
                List.of(observed.id()), .7, 0, "");
        for (int i = 0; i < 3; i++) {
            identity.revise("world", "bridge " + i, "Bridge route is open " + i, "Bridge inspection",
                    List.of(observed.id()), .7, 0, "");
        }
        String context = identity.decisionContext("bridge route inspection", "en_us", 1400);
        assertTrue(context.contains("Replenish provisions"), context);
        assertEquals(3, context.lines().count());
        assertTrue(context.contains("Bridge route is open"), "Relevant world evidence still has room");
        assertTrue(context.length() <= 1400);
    }
}
