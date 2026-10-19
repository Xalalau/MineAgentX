package com.mineagent.tools.management;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.tools.i18n.DiscoveryMessages;
import java.util.*;

/** Technical capability groups, not a natural-language intent classifier or record search. */
final class DiscoveryCatalog {
    private static final Map<String, List<String>> GROUPS = groups();
    private DiscoveryCatalog() {}

    private static Map<String, List<String>> groups() {
        var groups = new LinkedHashMap<String, List<String>>();
        groups.put("construction", List.of("construction", "execute_construction"));
        groups.put("identity", List.of("identity_memory"));
        groups.put("observation", List.of("scan_nearby_entities", "inspect_block", "get_owner_status", "get_world_info"));
        groups.put("storage", List.of("inspect_block_storage"));
        groups.put("combat", List.of("ranged_attack"));
        groups.put("inventory", List.of("drop_items"));
        groups.put("interaction", List.of("interact_entity", "inspect_gui", "close_gui"));
        groups.put("location", List.of("locate_structure", "locate_biome"));
        groups.put("skills", List.of("load_skill", "list_learned_skills", "execute_skill"));
        groups.put("memory", List.of("recall_memory"));
        groups.put("mechanisms", List.of("explore_mechanism"));
        groups.put("team", List.of("coordinate_team"));
        groups.put("other", List.of());
        return Collections.unmodifiableMap(groups);
    }

    static Map<String, List<String>> available(Collection<Tool> tools) {
        var available = new LinkedHashMap<String, List<String>>();
        GROUPS.keySet().forEach(category -> available.put(category, new ArrayList<>()));
        for (Tool tool : tools) {
            String name = tool.name();
            if (AgentLoop.isCoreTool(name) && !name.equals("identity_memory")) continue;
            String category = GROUPS.entrySet().stream().filter(e -> e.getValue().contains(name))
                    .map(Map.Entry::getKey).findFirst().orElse("other");
            available.get(category).add(name);
        }
        return available;
    }

    static JsonArray index(Map<String, List<String>> available) {
        var index = new JsonArray();
        available.forEach((category, names) -> {
            if (names.isEmpty()) return;
            var entry = new JsonObject();
            entry.addProperty("category", category);
            entry.addProperty("capability", DiscoveryMessages.text("category." + category));
            var ids = new JsonArray();
            names.forEach(ids::add);
            entry.add("tool_names", ids);
            index.add(entry);
        });
        return index;
    }
}
