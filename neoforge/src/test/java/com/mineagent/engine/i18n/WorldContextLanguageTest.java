package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldContextLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void actionLedgerLocalizesTruthConstraintButNotCorrelationIdsOrStatuses() {
        var ledger = new com.mineagent.engine.world.ActionTransactionLedger();
        RuntimeLanguage.configure("pt_br");
        assertEquals("Transações de ações: nenhuma\n", ledger.summarizeForPrompt(1L));
        ledger.begin("call_exact_1", "auto_mine", "mod:drop", null, 1L);
        String summary = ledger.summarizeForPrompt(1L);
        assertTrue(summary.startsWith("Transações de ações:\n- call_exact_1 auto_mine status=dispatched pre=unknown post=unknown\n"), summary);
        assertTrue(summary.endsWith("Somente ações VERIFIED podem ser consideradas concluídas; tente novamente ou replaneje ações não verificadas.\n"));
    }

    @Test void assetsRevealOnlyBoundedObservedInventoryAndFacilities() {
        var assets = new com.mineagent.engine.world.WorldAssetIndex();
        var position = new com.mineagent.engine.world.WorldAssetIndex.Position("mod:moon", 1, 64, -2);
        var item = new com.mineagent.engine.world.WorldAssetIndex.ItemObservation(0, "mod:ore", 3, 0, 0, java.util.Set.of("mod:capability"), 0.0);
        assets.observeInventory(position, java.util.List.of(item), 1L);
        assets.observeContainer("mod:chest", position, java.util.List.of(item), true, 1L);
        RuntimeLanguage.configure("pt_br");
        String summary = assets.summarizeForPrompt(position, 2L);
        assertTrue(summary.startsWith("Recursos do mundo (evidências observadas):\n- carregados: mod:orex3 \n"), summary);
        assertTrue(summary.contains("- armazenado mod:orex3 @ mod:moon:1,64,-2 capacidades=mod:capability idade_ticks=1"), summary);
        assertTrue(summary.endsWith("Use resolve_need antes de produzir um substituto quando houver incerteza sobre posse ou localização.\n"));
    }

    @Test void beliefSummarySeparatesObservedEvidenceFromContestedRules() {
        var beliefs = new com.mineagent.engine.world.BeliefState();
        RuntimeLanguage.configure("pt_br");
        assertEquals("Crenças: ainda não há evidências duradouras", beliefs.summarizeForPrompt());
        beliefs.observeFact("mod:ore", "count", "3", 0.8, "server", 1L);
        beliefs.observeRuleOutcome("mod:moon", "auto_mine", "mod:drop", true, "literal {0}", 1L);
        beliefs.observeRuleOutcome("mod:moon", "auto_mine", "mod:drop", false, "literal {0}", 2L);
        assertEquals("Crenças apoiadas em evidências:\n- auto_mine em mod:moon -> mod:drop confiança=0.50 CONTESTADA evidência=literal {0}\n- observado mod:ore count 3 confiança=0.80\n", beliefs.summarizeForPrompt());
    }

    @Test void semanticRecallUsesLocalizedProseWithoutTranslatingFactIdsOrValues() {
        var world = new SemanticWorldModel();
        world.observe("mod:machine", "status", "literal {0} [KEEP]", null, 1.0, "mod:observer", "call_1", 1L, 0L, true);
        assertTrue(world.summarizeForPrompt(1L).startsWith("Semantic world model (temporal evidence):\n"));
        RuntimeLanguage.configure("pt_br");
        assertEquals("Modelo semântico do mundo (evidências temporais):\n- mod:machine status=literal {0} [KEEP] confiança=1.00 idade=0 duradouro\nTrate fatos expirados ou de baixa confiança como desconhecidos e reúna evidências reversíveis.\n", world.summarizeForPrompt(1L));
        assertEquals("Memória de longo prazo relevante:\n- mod:machine status=literal {0} [KEEP] fonte=mod:observer idade_ticks=1\n", world.recallForPrompt("mod:machine", null, 2L, 1));
        assertEquals("", world.recallForPrompt("unrelated", null, 2L, 1));
    }
}
