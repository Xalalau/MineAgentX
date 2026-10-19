package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationContextLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void generatedAdaptersLocalizeOnlyTheirOwnedDescriptionAndKeepTypedFailureCorrelation() {
        var world = new SemanticWorldModel();
        var skills = new com.mineagent.engine.skill.SkillLibrary();
        var fingerprint = new com.mineagent.engine.exploration.EnvironmentFingerprint("1.21.1", "neoforge", "mods", "registries");
        var explorer = new MechanismExplorer(world, (a,b,c,d) -> {}, skills, fingerprint,
                (id, tool, args) -> new MechanismExplorer.CompensationResult(true, false, true, "literal compensation evidence"));
        RuntimeLanguage.configure("pt_br");
        for (int i = 1; i <= 2; i++) {
            long tick = i * 100L;
            String subject = "machine:" + i;
            var proposal = explorer.propose("mod:unknown", "raw hypothesis {0}", "interact_at", "{}", subject, "state", "active", MechanismExplorer.Risk.MEDIUM,
                    "context:" + i, true, "close_gui", "{}", 4, 0.8, tick);
            assertTrue(proposal.accepted(), proposal.message());
            String call = "call_exact_" + i;
            explorer.onToolDispatched(call, "interact_at", "{}", tick + 1L);
            world.observe(subject, "state", "active", null, 0.9, "inspect_gui", call, tick + 2L, 40L, false);
            explorer.onToolResult(call, "interact_at", true, false, "literal external evidence", tick + 2L);
        }
        var rule = explorer.knowledgeBase().rules().iterator().next();
        assertNotNull(rule.adapterSkill());
        assertEquals("Adaptador verificado para mod:unknown: raw hypothesis {0}", skills.get(rule.adapterSkill()).orElseThrow().description());
        explorer.onAdapterOutcome(rule.adapterSkill(), false, ContextMessages.text("skill.postcondition"), 300L);
        assertTrue(skills.get(rule.adapterSkill()).isEmpty());
    }

    @Test void explorationLifecycleKeepsLiteralEvidenceAndDoesNotInventNegativeKnowledge() {
        var explorer = new MechanismExplorer(new SemanticWorldModel(), (a,b,c,d) -> {});
        RuntimeLanguage.configure("pt_br");
        var armed = explorer.propose("mod:unknown", "raw hypothesis", "inspect_block", "{}", "mod:unknown", "state", "active", MechanismExplorer.Risk.LOW, 1L);
        explorer.onToolDispatched("call_exact", "inspect_block", "{}", 2L);
        assertEquals("Sondagem enviada com args={}", explorer.get(armed.experiment().id()).evidence());
        explorer.onToolResult("call_exact", false, false, "literal evidence {0}", 3L);
        assertEquals(MechanismExplorer.Status.INCONCLUSIVE, explorer.get(armed.experiment().id()).status());
        assertEquals("A ação de sondagem falhou antes de testar a hipótese: literal evidence {0}", explorer.get(armed.experiment().id()).evidence());
        assertTrue(explorer.knowledgeBase().rules().stream().allMatch(rule -> rule.supports() == 0 && rule.refutations() == 0));
        var pending = explorer.propose("mod:other", "raw hypothesis", "inspect_block", "{}", null, null, null, MechanismExplorer.Risk.LOW, 4L);
        var restored = new MechanismExplorer(new SemanticWorldModel(), (a,b,c,d) -> {});
        restored.importState(explorer.exportState());
        assertEquals("O experimento foi interrompido por uma reinicialização do jogo; a posse do corpo não foi restaurada", restored.get(pending.experiment().id()).evidence());
        assertEquals("literal evidence {0}", explorer.exportState().experiments().getFirst().evidence().substring("A ação de sondagem falhou antes de testar a hipótese: ".length()));
    }

    @Test void explorationAdmissionAndRecommendationExplainEvidenceLimitsInCurrentLanguage() {
        var explorer = new MechanismExplorer(new SemanticWorldModel(), (a,b,c,d) -> {});
        RuntimeLanguage.configure("pt_br");
        assertEquals("Experimentos de alto risco exigem uma ação normal explicitamente orientada pelo dono",
                explorer.propose("mod:unknown", "raw hypothesis", "inspect_block", "{}", null, null, null, MechanismExplorer.Risk.HIGH, 1L).message());
        assertEquals("Texto, contexto ou argumentos JSON do experimento são inválidos ou grandes demais",
                explorer.propose("mod:unknown", "raw hypothesis", "inspect_block", "invalid", null, null, null, MechanismExplorer.Risk.LOW, 1L).message());
        assertEquals("Nenhuma opção cabe no orçamento restante de exploração reversível", explorer.recommend(java.util.List.of(), "mod:unknown", 1L).message());
        var candidate = new MechanismExplorer.ProbeCandidate("inspect_block", "{}", MechanismExplorer.Risk.LOW, true, null, "{}", 1, 0.7);
        assertEquals("Prefira a maior informação esperada por custo limitado; verifique apenas uma sondagem", explorer.recommend(java.util.List.of(candidate), "mod:unknown", 1L).message());
    }

    @Test void controlledExplorationLocalizesObservedContextWithoutInventingKnowledge() {
        var explorer = new MechanismExplorer(new SemanticWorldModel(), (experiment, supported, evidence, tick) -> {});
        RuntimeLanguage.configure("pt_br");
        assertEquals("Adaptação de mecanismos: inativa\n", explorer.summarizeForPrompt());
        var rejected = explorer.propose("mod:unknown", "raw hypothesis {0}", "not_allowed", "{}", "mod:unknown", "state_exact", "value_exact", MechanismExplorer.Risk.LOW, 1L);
        assertFalse(rejected.accepted());
        assertEquals("Ferramenta de sondagem não permitida para exploração controlada: not_allowed", rejected.message());
        var armed = explorer.propose("mod:unknown", "raw hypothesis {0}", "inspect_block", "{}", "mod:unknown", "state_exact", "value_exact", MechanismExplorer.Risk.LOW, 1L);
        assertTrue(armed.accepted());
        assertEquals("Experimento preparado; chame inspect_block uma vez com os argumentos exatos propostos", armed.message());
        assertEquals("Chame a ferramenta de sondagem declarada uma vez", armed.experiment().evidence());
        String context = explorer.summarizeForPrompt("mod:unknown");
        assertTrue(context.startsWith("Adaptação de mecanismos:\n"), context);
        assertTrue(context.contains("mod:unknown state_exact=value_exact"), context);
        assertTrue(context.endsWith("Desconhecido não significa falso: prefira uma sondagem reversível com orçamento limitado; exija apoio independente antes da reutilização.\n"));
        assertFalse(context.contains("[EXPERIMENT"));
    }
}
