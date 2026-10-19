package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.memory.PlaceEventMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryContextLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void learnedSalienceIsEvidenceNotAnAssignedTaste() {
        var memory = new com.mineagent.engine.memory.ImportanceEvaluator();
        memory.importWeights(java.util.Map.of("auto_mine:mod:ore", 0.95f), 3);
        RuntimeLanguage.configure("pt_br");
        assertEquals("Relevância aprendida: mod:ore", memory.summarizeForPrompt());
        RuntimeLanguage.configure("en_us");
        assertEquals("Learned salience: mod:ore", memory.summarizeForPrompt());
    }

    @Test void experienceRecallLocalizesHeadingAndPreservesTypedOutcomes() {
        var memory = new com.mineagent.engine.memory.ExperienceStore();
        memory.record("call_exact", "auto_mine", "mod:ore", com.mineagent.api.task.TaskState.SUCCESS, null, "literal evidence {0}", 1L);
        RuntimeLanguage.configure("pt_br");
        assertEquals("Experiências verificadas relevantes:\n- auto_mine -> SUCCESS: literal evidence {0}\n", memory.summarizeForPrompt("mod:ore"));
        assertEquals("", memory.summarizeForPrompt("unrelated"));
    }

    @Test void spatialRecallKeepsStoredLabelsAndCapsProgressiveDetail() {
        var memory = new com.mineagent.engine.memory.CognitiveMap();
        var poi = new com.mineagent.engine.memory.CognitiveMap.PointOfInterest("resource:mod_ore", "mod:ore {0}", 1, 64, -2, "mod:moon", 1L, 0.8f, 3, true, 2L);
        memory.importAll(java.util.List.of(poi));
        RuntimeLanguage.configure("pt_br");
        assertEquals("## Memória espacial\n- mod:ore {0} em (1,64,-2) [mod:moon] [confirmado x3]\n", memory.summarizeForPrompt("mod:ore", 1));
        assertEquals("", memory.summarizeForPrompt("mod:ore", 0));
        assertEquals("", memory.summarizeForPrompt("unrelated", 1));
    }

    @Test void allGeneratedFailureAdviceIsBilingualWithoutActionRecipes() {
        String[][] micro = {{"not found", "Alvo não encontrado"}, {"denied", "Não foi possível executar"},
                {"timeout", "excedeu o tempo limite"}, {"busy", "Conflito no estado"}, {"raw failure", "falhou"}};
        String[][] meso = {{"too complex", "complexa demais"}, {"insufficient", "Recursos insuficientes"},
                {"blocked", "Navegação bloqueada"}, {"danger", "ambiente é perigoso"}, {"raw failure", "falhou"}};
        RuntimeLanguage.configure("pt_br");
        for (String[] item : micro) {
            var reflection = new com.mineagent.engine.memory.ReflectionSystem();
            reflection.recordFailure("mod:goal", "auto_mine", item[0]);
            String lesson = reflection.summarizeForPrompt();
            assertTrue(lesson.contains(item[1]), lesson);
            assertFalse(lesson.contains("find_block"));
            assertFalse(lesson.contains("look_around"));
        }
        for (String[] item : meso) {
            var reflection = new com.mineagent.engine.memory.ReflectionSystem();
            reflection.recordFailedTask("mod:goal", "raw plan", item[0]);
            assertTrue(reflection.summarizeForPrompt().contains(item[1]), reflection.summarizeForPrompt());
        }
        var reflection = new com.mineagent.engine.memory.ReflectionSystem();
        reflection.recordPatternFailure("mod:pattern", 4, "raw evidence");
        assertTrue(reflection.summarizeForPrompt().contains("Padrão de falhas repetidas"));
        RuntimeLanguage.configure("en_us");
        var english = new com.mineagent.engine.memory.ReflectionSystem();
        english.recordFailedTask("mod:goal", "raw plan", "insufficient");
        assertTrue(english.summarizeForPrompt().contains("Insufficient resources"));
        assertFalse(english.summarizeForPrompt().contains("find_block"));
    }

    @Test void failureLessonsUseCurrentLanguageWithoutRewritingImportedMemory() {
        var reflection = new com.mineagent.engine.memory.ReflectionSystem();
        RuntimeLanguage.configure("pt_br");
        reflection.recordFailure("mod:goal", "auto_mine", "invalid parameter {0}");
        assertEquals("## Lições a lembrar\n- Parâmetros inválidos em auto_mine. Verifique o formato e os campos obrigatórios. Parâmetros atuais: mod:goal\n", reflection.summarizeForPrompt());
        assertTrue(reflection.formatFailuresForPrompt("mod:goal").startsWith("## Memórias de falhas relevantes\n- Tarefa: auto_mine(mod:goal)\nLição: "));
        reflection.importAll(java.util.List.of(new com.mineagent.engine.memory.ReflectionSystem.Reflection(
                "micro", "mod:goal", "raw", "literal stored memory {0} [KEEP]", 1L, "auto_mine")), java.util.Map.of());
        assertEquals("## Lições a lembrar\n- literal stored memory {0} [KEEP]\n", reflection.summarizeForPrompt());
    }

    @Test void tieredRecallKeepsTechnicalTierAndEvidenceWhileLocalizingAdvice() {
        var memory = new com.mineagent.engine.memory.LayeredMemory();
        memory.remember(com.mineagent.engine.memory.LayeredMemory.Tier.SEMANTIC,
                "mod:ore", "literal {1} [KEEP]", "server", 0.8, 1L, 0L, "mod:pack", "mod:moon");
        RuntimeLanguage.configure("pt_br");
        assertEquals("Memória em camadas (evidências relacionadas ao objetivo):\n- [semantic] mod:ore=literal {1} [KEEP] confiança=0.80 confiabilidade=0.67\nUse a memória como evidência; verifique fatos voláteis antes de ações irreversíveis.\n",
                memory.summarizeForPrompt("mod:ore", 2L, 700));
        assertEquals("Memória em camadas: nenhuma\n", memory.summarizeForPrompt("unrelated", 2L, 700));
    }

    @Test void locationRecallLocalizesOnlyOwnedProseAndRespectsProgressiveLimit() {
        PlaceEventMemory memory = new PlaceEventMemory();
        memory.remember("resource", "mod:raw_ore", 1, 64, -2, "mod:moon", 1L, "player text {0} [KEEP]");
        RuntimeLanguage.configure("en_us");
        assertEquals("## Location memories (recently seen resources/events)\n- mod:raw_ore at (1,64,-2) [mod:moon] — player text {0} [KEEP]\n(Explored 0 chunks)\n", memory.summarizeForPrompt("", 1));
        RuntimeLanguage.configure("pt_br");
        assertEquals("## Memórias de locais (recursos/eventos vistos recentemente)\n- mod:raw_ore em (1,64,-2) [mod:moon] — player text {0} [KEEP]\n(0 chunks explorados)\n", memory.summarizeForPrompt("", 1));
        assertEquals("", memory.summarizeForPrompt("", 0));
        assertEquals("", memory.summarizeForPrompt("unrelated", 1));
    }
}
