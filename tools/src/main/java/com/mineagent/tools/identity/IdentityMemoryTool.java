package com.mineagent.tools.identity;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.identity.IdentityText;
import com.mineagent.engine.task.TaskContext;
import java.util.*;
import java.util.function.Consumer;

/** Native, synchronous personal-memory tool. No world reads, model-generated observations or extra HTTP requests. */
public final class IdentityMemoryTool implements Tool {
    @Override public String name() { return "identity_memory"; }
    private static String text(String en, String pt) {
        return IdentityText.text(RuntimeLanguage.language().id(), en, pt);
    }
    @Override public String description() {
        return text("Progressively recall relevant personal commitments, actual evidence and revision history, or retain a justified preference, goal or world belief during an admitted reflection opportunity. A player's report remains distinct from server observation. Revisions require known evidence identifiers, a reason and the current revision; replacing a view requires new evidence and an explicit reconsideration. This tool cannot create evidence or assign a personality.",
                "Recupere progressivamente compromissos pessoais relevantes, evidências reais e revisões, ou retenha uma preferência, objetivo ou crença sobre o mundo durante uma oportunidade admitida de reflexão. Relato do jogador é diferente de observação do servidor. Revisões exigem identificadores conhecidos de evidência, motivo e revisão atual; substituir uma visão exige nova evidência e reconsideração explícita. Esta ferramenta não cria evidências nem atribui personalidade.");
    }
    @Override public Map<String, Object> parameterSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("operation", Map.of("type", "string", "enum", List.of("recall", "revise"), "description", text("Read relevant memory or revise with evidence.", "Leia memórias relevantes ou revise com evidências.")));
        properties.put("query", property("string", text("Short relevance query for recall; empty returns a few recent observations.", "Consulta curta de relevância; vazia retorna algumas observações recentes.")));
        properties.put("kind", Map.of("type", "string", "enum", List.of("preference", "goal", "world"), "description", text("Kind of learned commitment.", "Tipo de compromisso aprendido.")));
        properties.put("subject", property("string", text("Stable short subject; exact subject on recall includes its revision history.", "Assunto curto e estável; assunto exato na consulta inclui seu histórico de revisões.")));
        properties.put("statement", property("string", text("Current tentative preference, goal or world belief; at most 300 characters.", "Preferência, objetivo ou crença atual provisória; até 300 caracteres.")));
        properties.put("reason", property("string", text("Why the cited evidence supports this particular view; at most 300 characters.", "Por que as evidências citadas apoiam esta visão específica; até 300 caracteres.")));
        properties.put("evidence_ids", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1, "maxItems", 3, "description", text("One to three existing evidence identifiers: on recall, retrieve those exact records; on revise, cite their support. Never invent identifiers.", "De um a três identificadores existentes: na consulta, recupere esses registros exatos; ao revisar, cite seu apoio. Nunca invente identificadores.")));
        properties.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1, "description", text("Uncertain support, not certainty; player reports are capped below observations.", "Grau de apoio incerto, não certeza; relatos do jogador têm limite inferior às observações.")));
        properties.put("expected_revision", Map.of("type", "integer", "minimum", 0, "description", text("Zero for a new subject; otherwise the exact current revision from recall.", "Zero para assunto novo; caso contrário, a revisão atual exata retornada pela consulta.")));
        properties.put("reconsideration", property("string", text("For a revision, explain why new evidence changes the previous reason; at most 300 characters.", "Ao revisar, explique por que a nova evidência altera o motivo anterior; até 300 caracteres.")));
        return Map.of("type", "object", "properties", properties, "required", List.of("operation"), "additionalProperties", false);
    }
    private static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }
    @Override public int defaultTimeoutSeconds() { return 5; }
    @Override public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player, Consumer<String> reply) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            JsonObject error = new JsonObject(); error.addProperty("error", "agent_loop_unavailable");
            error.addProperty("message", text("Personal memory is unavailable.", "A memória pessoal está indisponível."));
            reply.accept(error.toString());
        } else reply.accept(loop.identityMemory(args));
    }
}
