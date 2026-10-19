package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerceptionContextLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void executorDeliberationLocalizesOwnedAdviceWithoutChangingGateOrRawEvidence() throws Exception {
        var cognition = new com.mineagent.engine.cognition.RealtimeCognition(null);
        var frame = new com.mineagent.engine.cognition.SituationSnapshot(1L,
                new com.mineagent.engine.cognition.SituationSnapshot.Position("mod:moon", 1, 64, -2),
                null, null, null, null, java.util.List.of());
        var decision = new com.mineagent.engine.cognition.TacticalDecision(
                com.mineagent.engine.cognition.TacticalDecision.Posture.REPLAN_BLOCKED_GOAL,
                0.8, "literal blocker {0}", java.util.List.of(), false, true, 1L);
        var event = com.mineagent.engine.cognition.RealtimeCognition.class.getDeclaredMethod("deliberationEvent",
                com.mineagent.engine.cognition.SituationSnapshot.class, com.mineagent.engine.cognition.TacticalDecision.class);
        event.setAccessible(true);
        RuntimeLanguage.configure("pt_br");
        assertEquals("[COGNITION_DECISION] replan_blocked_goal confidence=0.80 reason=literal blocker {0}; "
                + "as evidências do executor invalidaram a ação atual. Replaneje com base nas evidências atuais, em vez de repetir sem mudanças.", event.invoke(cognition, frame, decision));
        assertNull(event.invoke(cognition, frame, decision));
    }

    @Test void tacticalEvidenceIsLocalizedWithoutTranslatingPostureIdsOrRawBlockers() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("nenhuma observação do servidor ainda", com.mineagent.engine.cognition.TacticalDecision.initial().reason());
        var frame = new com.mineagent.engine.cognition.SituationSnapshot(1L,
                new com.mineagent.engine.cognition.SituationSnapshot.Position("mod:moon", 1, 64, -2),
                null, null, null, null, java.util.List.of());
        var decision = new com.mineagent.engine.cognition.TacticalPlanner().decide(frame);
        assertEquals(com.mineagent.engine.cognition.TacticalDecision.Posture.OBSERVE_BEFORE_COMMIT, decision.posture());
        assertTrue(decision.reason().startsWith("Obtenha evidências antes de escolher uma ação irreversível"), decision.reason());
        assertTrue(decision.compact().startsWith("observe_before_commit"), decision.compact());
        assertFalse(decision.reason().contains("Acquire evidence"));
    }

    @Test void realtimeContextUsesNaturalLocalizedEvidenceAndStableWireFlags() throws Exception {
        var cognition = new com.mineagent.engine.cognition.RealtimeCognition(null);
        RuntimeLanguage.configure("pt_br");
        assertEquals("Cognição em tempo real: aguardando o primeiro quadro do servidor\n", cognition.summarizeForPrompt());
        var frame = new com.mineagent.engine.cognition.SituationSnapshot(1L,
                new com.mineagent.engine.cognition.SituationSnapshot.Position("mod:moon", 1, 64, -2),
                null, null, null, null, java.util.List.of());
        var field = com.mineagent.engine.cognition.RealtimeCognition.class.getDeclaredField("latestFrame");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var latest = (java.util.concurrent.atomic.AtomicReference<com.mineagent.engine.cognition.SituationSnapshot>) field.get(cognition);
        latest.set(frame);
        String summary = cognition.summarizeForPrompt();
        assertTrue(summary.startsWith("Cognição em tempo real (evidências do servidor):\n"), summary);
        assertTrue(summary.contains("mod:moon:1,64,-2"), summary);
        assertTrue(summary.endsWith("O controle tático local e de sobrevivência não exige uma chamada de ferramenta pelo modelo; delibere somente quando o quadro indicar deliberation=true.\n"));
    }

    @Test void teamCoordinationLocalizesGeneratedProseWithoutTranslatingObjectives() {
        var owner = java.util.UUID.randomUUID();
        var self = java.util.UUID.randomUUID();
        var ally = java.util.UUID.randomUUID();
        RuntimeLanguage.configure("pt_br");
        try {
            assertEquals("Quadro da equipe: sem relatos atuais de aliados\n", com.mineagent.engine.cognition.TeamBlackboard.summarize(owner, self, 1L));
            com.mineagent.engine.cognition.TeamBlackboard.commit(owner, self, "raw objective {0}", "raw role", null, 1L);
            com.mineagent.engine.cognition.TeamBlackboard.commit(owner, ally, "raw objective {0}", "raw role", null, 1L);
            String summary = com.mineagent.engine.cognition.TeamBlackboard.summarize(owner, self, 2L);
            assertTrue(summary.startsWith("Quadro da equipe (evidências atuais):\n"), summary);
            assertTrue(summary.contains("2 companheiros reivindicam o objetivo 'raw objective {0}'"), summary);
            assertTrue(summary.endsWith("Coordene por compromissos e pedidos de apoio; não duplique o objetivo de um aliado sem motivo.\n"));
            assertTrue(com.mineagent.engine.cognition.TeamBlackboard.tryCommit(owner, java.util.UUID.randomUUID(), "raw objective {0}", "raw role", null, 2L).reason().startsWith("Objetivo já reivindicado por "));
        } finally { com.mineagent.engine.cognition.TeamBlackboard.clearAll(); }
    }

    @Test void orderedCognitiveEvidenceLocalizesHeadingAndKeepsNewestBoundedEntries() {
        var state = new com.mineagent.engine.cognition.CognitiveState();
        state.append("task_outcome", "call_old", "raw old", 1L);
        state.append("task_outcome", "call_exact", "literal {0}", 2L);
        RuntimeLanguage.configure("pt_br");
        assertEquals("Registro cognitivo (evidências ordenadas):\n- #2 task_outcome call_exact=literal {0} tick=2\n", state.summarizeForPrompt(1));
    }
}
