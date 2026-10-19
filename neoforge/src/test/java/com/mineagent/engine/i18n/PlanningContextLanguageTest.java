package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.planning.PlanGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanningContextLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    static PlanGraph graph() {
        var graph = new PlanGraph();
        graph.replacePlan("raw goal {0}", List.of(new PlanGraph.DraftNode("step_exact", "raw description", "raw criterion", "medium", List.of(), PlanGraph.NodeStatus.PENDING)), List.of());
        return graph;
    }

    @Test void acceptedPlanWarningsDoNotTurnModelClaimsIntoVerifiedExecutorEvidence() {
        var graph = new PlanGraph();
        RuntimeLanguage.configure("pt_br");
        var a = new PlanGraph.DraftNode("mod:a", "raw a", "raw proof", "medium", java.util.List.of(), PlanGraph.NodeStatus.VERIFIED);
        var b = new PlanGraph.DraftNode("mod:b", "raw b", "raw proof", "medium", java.util.List.of(), PlanGraph.NodeStatus.VERIFIED);
        var update = graph.replacePlan("raw goal", java.util.List.of(a, b), java.util.List.of());
        assertTrue(update.accepted());
        assertEquals(java.util.List.of("A etapa 'mod:a' não foi marcada como verificada porque não há evidências de sucesso do executor", "A etapa 'mod:b' não foi marcada como verificada porque não há evidências de sucesso do executor", "Apenas uma etapa pode controlar o corpo; a etapa 'mod:b' foi mantida pendente"), update.warnings());
        assertEquals(PlanGraph.NodeStatus.IN_PROGRESS, graph.exportState().nodes().getFirst().status());
        assertEquals(PlanGraph.NodeStatus.PENDING, graph.exportState().nodes().getLast().status());
        var dependent = new PlanGraph.DraftNode("mod:d", "raw d", "raw proof", "medium", java.util.List.of("mod:a"), PlanGraph.NodeStatus.IN_PROGRESS);
        var pendingA = new PlanGraph.DraftNode("mod:a", "raw a", "raw proof", "medium", java.util.List.of(), PlanGraph.NodeStatus.PENDING);
        assertTrue(graph.replacePlan("raw goal", java.util.List.of(pendingA, dependent), java.util.List.of()).warnings().contains("A etapa 'mod:d' não pode começar antes da verificação das dependências"));
        var missing = new PlanGraph.DraftNode("mod:d", "raw d", "raw proof", "medium", java.util.List.of("mod:missing"), PlanGraph.NodeStatus.PENDING);
        assertEquals(java.util.List.of("A etapa 'mod:d' depende da etapa ausente ou não verificada 'mod:missing'"), graph.repairPlan("raw goal", java.util.List.of(missing), null, null).warnings());
    }

    @Test void rejectedPlanDiagnosticsLocalizeTrustedWarningsAndKeepIdsLiteral() {
        var graph = new PlanGraph();
        RuntimeLanguage.configure("pt_br");
        var node = new PlanGraph.DraftNode("mod:step", "raw detail", "raw acceptance", "medium", java.util.List.of("mod:missing"), PlanGraph.NodeStatus.PENDING);
        var rejected = graph.replacePlan("raw goal", java.util.Arrays.asList(null, node, node), java.util.List.of());
        assertFalse(rejected.accepted());
        assertEquals(java.util.List.of("O plano contém um nó sem id", "Id de etapa duplicado 'mod:step'", "A etapa 'mod:step' depende da etapa ausente 'mod:missing'"), rejected.warnings());
        assertEquals(0L, graph.exportState().revision());
        var cycle = new PlanGraph.DraftNode("mod:step", "raw detail", "raw acceptance", "medium", java.util.List.of("mod:step"), PlanGraph.NodeStatus.PENDING);
        assertEquals(java.util.List.of("O grafo de dependências do plano contém um ciclo"), graph.replacePlan("raw goal", java.util.List.of(cycle), java.util.List.of()).warnings());
    }

    @Test void rollingSignalsLocalizeProseButKeepInternallyConsumedEventDiscriminators() {
        var rolling = new com.mineagent.engine.planning.HierarchicalRollingPlanner(new PlanGraph(), new com.mineagent.engine.world.SemanticWorldModel());
        rolling.onPlanReplaced("raw goal {0}", 1L);
        RuntimeLanguage.configure("pt_br");
        var signal = rolling.tick(2L);
        assertEquals(com.mineagent.engine.planning.HierarchicalRollingPlanner.Reason.PLAN_MISSING, signal.reason());
        assertEquals("O objetivo estratégico não tem decomposição apoiada por verificação", signal.detail());
        assertTrue(signal.event().startsWith("[ROLLING_REPLAN] reason=plan_missing goal=raw goal {0} active_step=none detail="));
        assertTrue(signal.event().endsWith("; preserve as etapas verificadas e repare apenas a próxima janela executável"));
    }

    @Test void reservationsLocalizeOnlyTheTemporaryClaimSummary() {
        var ledger = new com.mineagent.engine.planning.ResourceReservationLedger();
        ledger.reserve("claim_exact", "mod:station", "raw purpose {0}", 1L, 20L, true);
        RuntimeLanguage.configure("pt_br");
        assertEquals("Reservas (reivindicações temporárias):\n- mod:station para raw purpose {0} até 21\n", ledger.summarizeForPrompt(2L));
        assertEquals("Reservas: nenhuma\n", ledger.summarizeForPrompt(22L));
    }

    @Test void rollingPlanLocalizesGuidanceWhileRetainingOnlyNearTermWindow() {
        var graph = graph();
        var rolling = new com.mineagent.engine.planning.HierarchicalRollingPlanner(graph, new com.mineagent.engine.world.SemanticWorldModel());
        rolling.onPlanReplaced("raw goal {0}", 1L);
        RuntimeLanguage.configure("pt_br");
        String summary = rolling.summarizeForPrompt();
        assertTrue(summary.startsWith("Planejamento hierárquico contínuo:\n- objetivo_estratégico=raw goal {0}"), summary);
        assertTrue(summary.contains("step_exact(pending): raw description"), summary);
        assertTrue(summary.endsWith("Planeje apenas a próxima janela útil; preserve o trecho verificado, as dependências explícitas, as restrições, as contingências e os critérios observáveis de sucesso.\n"));
    }

    @Test void planSummaryLocalizesLabelsNotGoalDescriptionsOrStatusIds() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("Plano: nenhum", new PlanGraph().summarizeForPrompt());
        String summary = graph().summarizeForPrompt();
        assertEquals("Objetivo do plano: raw goal {0} (revisão 1, 0% verificado, status=active, reparos=0)\n- [PENDING] step_exact: raw description\n", summary);
    }
}
