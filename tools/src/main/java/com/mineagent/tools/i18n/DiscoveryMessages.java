package com.mineagent.tools.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.util.Map;

/** Strict bilingual discovery metadata; technical selectors remain literal. */
public final class DiscoveryMessages {
    private static final MessageCatalog CATALOG = new MessageCatalog(Map.of(
        Language.EN_US, Map.ofEntries(
            Map.entry("unavailable", "Companion discovery is unavailable; no tools were revealed."),
            Map.entry("invalid", "Invalid discovery selection in field {0}; no tools were revealed."),
            Map.entry("categories", "Exact technical category IDs from the capability index; no natural-language query."),
            Map.entry("tool_names", "Exact registered tool names from the index; IDs are case-sensitive and are not trimmed or translated."),
            Map.entry("limit", "Maximum specialized tools to reveal in this call (default 4, range 1–4). Oversized selections are rejected without partial exposure."),
            Map.entry("limit_exceeded", "Selection contains {0} specialized tools, exceeding limit {1}; nothing was revealed."),
            Map.entry("selected", "Only the selected specialized schemas are attached to the next request; already available tools remain available."),
            Map.entry("description", "Discover specialized capabilities by exact category or tool name. An empty object returns a compact index without revealing schemas or saved records."),
            Map.entry("index", "Select categories or exact tool_names to reveal only the requested specialized schemas in the next request."),
            Map.entry("category.construction", "Versioned designs, previews, site assessment and physical construction"),
            Map.entry("category.identity", "Evidence-backed identity, commitments and reasoned revisions (already available)"),
            Map.entry("category.observation", "Entities, blocks, owner and world observations"),
            Map.entry("category.storage", "Container contents"),
            Map.entry("category.combat", "Ranged combat under server policy"),
            Map.entry("category.inventory", "Dropping carried items"),
            Map.entry("category.interaction", "Entity and open-interface interactions"),
            Map.entry("category.location", "Biome and structure locations"),
            Map.entry("category.skills", "Skill knowledge and learned sequences"),
            Map.entry("category.memory", "Retrieval of retained observations"),
            Map.entry("category.mechanisms", "Bounded mechanism experiments"),
            Map.entry("category.team", "Companion coordination"),
            Map.entry("category.other", "Other registered specializations")),
        Language.PT_BR, Map.ofEntries(
            Map.entry("unavailable", "A descoberta do companheiro está indisponível; nenhuma ferramenta foi revelada."),
            Map.entry("invalid", "Seleção de descoberta inválida no campo {0}; nenhuma ferramenta foi revelada."),
            Map.entry("categories", "IDs técnicos exatos de categoria do índice de capacidades; sem consulta em linguagem natural."),
            Map.entry("tool_names", "Nomes exatos de ferramentas registradas do índice; IDs diferenciam maiúsculas e não são aparados nem traduzidos."),
            Map.entry("limit", "Máximo de ferramentas especializadas a revelar nesta chamada (padrão 4, intervalo 1–4). Seleções maiores são rejeitadas sem exposição parcial."),
            Map.entry("limit_exceeded", "A seleção contém {0} ferramentas especializadas, excedendo o limite {1}; nada foi revelado."),
            Map.entry("selected", "Somente os schemas especializados selecionados serão anexados à próxima requisição; ferramentas já disponíveis continuam disponíveis."),
            Map.entry("description", "Descubra capacidades especializadas por categoria ou nome exato de ferramenta. Um objeto vazio retorna um índice compacto sem revelar schemas ou registros salvos."),
            Map.entry("index", "Selecione categories ou tool_names exatos para revelar somente os schemas especializados solicitados na próxima requisição."),
            Map.entry("category.construction", "Projetos versionados, prévias, avaliação de terreno e construção física"),
            Map.entry("category.identity", "Identidade baseada em evidências, compromissos e revisões fundamentadas (já disponível)"),
            Map.entry("category.observation", "Observações de entidades, blocos, dono e mundo"),
            Map.entry("category.storage", "Conteúdo de recipientes"),
            Map.entry("category.combat", "Combate à distância sob a política do servidor"),
            Map.entry("category.inventory", "Soltar itens carregados"),
            Map.entry("category.interaction", "Interações com entidades e interfaces abertas"),
            Map.entry("category.location", "Localização de biomas e estruturas"),
            Map.entry("category.skills", "Conhecimento de habilidades e sequências aprendidas"),
            Map.entry("category.memory", "Recuperação de observações retidas"),
            Map.entry("category.mechanisms", "Experimentos limitados de mecanismos"),
            Map.entry("category.team", "Coordenação entre companheiros"),
            Map.entry("category.other", "Outras especializações registradas"))));
    private DiscoveryMessages() {}
    public static String text(String key, Object... arguments) {
        return CATALOG.text(RuntimeLanguage.language(), key, arguments);
    }
}
