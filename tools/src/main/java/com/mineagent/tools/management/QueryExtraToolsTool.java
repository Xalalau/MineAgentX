package com.mineagent.tools.management;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.tools.i18n.DiscoveryMessages;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Synchronous model-directed progressive capability discovery. */
public class QueryExtraToolsTool implements Tool {
    private static final int MAX_EXPOSED = 4;

    @Override public String name() { return "query_extra_tools"; }
    @Override public String description() { return DiscoveryMessages.text("description"); }
    @Override public Map<String, Object> parameterSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("categories", Map.of("type", "array", "description", DiscoveryMessages.text("categories"),
                "items", Map.of("type", "string", "enum", List.copyOf(DiscoveryCatalog.available(ToolRegistry.all()).keySet())),
                "uniqueItems", true, "maxItems", MAX_EXPOSED));
        properties.put("tool_names", Map.of("type", "array", "description", DiscoveryMessages.text("tool_names"),
                "items", Map.of("type", "string"), "uniqueItems", true, "maxItems", MAX_EXPOSED));
        properties.put("limit", Map.of("type", "integer", "description", DiscoveryMessages.text("limit"),
                "minimum", 1, "maximum", MAX_EXPOSED, "default", MAX_EXPOSED));
        return Map.of("type", "object", "properties", properties, "required", List.of(), "additionalProperties", false);
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                                       Consumer<String> reply) {
        var tools = ToolRegistry.all();
        var result = new JsonObject();
        var available = DiscoveryCatalog.available(tools);
        JsonObject invalid = validate(args, available.keySet());
        if (invalid != null) {
            reply.accept(invalid.toString());
            return;
        }
        var selected = new LinkedHashSet<String>();
        if (args.has("categories")) {
            for (var category : args.getAsJsonArray("categories")) selected.addAll(available.get(category.getAsString()));
        }
        if (args.has("tool_names")) {
            for (var name : args.getAsJsonArray("tool_names")) selected.add(name.getAsString());
        }
        var extras = new JsonArray();
        var alreadyAvailable = new JsonArray();
        selected.forEach(name -> { if (AgentLoop.isCoreTool(name)) alreadyAvailable.add(name); else extras.add(name); });
        int limit = args.has("limit") ? args.get("limit").getAsBigDecimal().intValueExact() : MAX_EXPOSED;
        if (extras.size() > limit) {
            result.addProperty("code", "discovery_limit_exceeded");
            result.addProperty("error", DiscoveryMessages.text("limit_exceeded", extras.size(), limit));
            reply.accept(result.toString());
            return;
        }
        if (!extras.isEmpty()) {
            var state = player == null || player.companionId() == null
                    ? Optional.<MineAgentEngine.CompanionState>empty()
                    : MineAgentEngine.getCompanion(player.companionId());
            if (state.isEmpty() || state.get().loop == null) {
                result.addProperty("code", "discovery_unavailable");
                result.addProperty("error", DiscoveryMessages.text("unavailable"));
                reply.accept(result.toString());
                return;
            }
            state.get().loop.exposeExtraTools(selected.stream().filter(name -> !AgentLoop.isCoreTool(name)).toList());
        }
        result.add("exposed_tools", extras);
        result.add("already_available_tools", alreadyAvailable);
        result.addProperty("exposed_count", extras.size());
        result.addProperty("total_extra", tools.stream().filter(t -> !AgentLoop.isCoreTool(t.name())).count());
        if (selected.isEmpty()) result.add("categories", DiscoveryCatalog.index(available));
        result.addProperty("next_step", DiscoveryMessages.text(selected.isEmpty() ? "index" : "selected"));
        reply.accept(result.toString());
    }

    /** Validate the whole selection before touching loop visibility; do not repair IDs or coerce types. */
    private static JsonObject validate(JsonObject args, Set<String> categories) {
        if (args == null) return invalid("args", JsonNull.INSTANCE);
        for (var entry : args.entrySet()) {
            String field = entry.getKey();
            JsonElement value = entry.getValue();
            boolean valid = switch (field) {
                case "categories" -> validNames(value, categories::contains);
                case "tool_names" -> validNames(value, name -> ToolRegistry.get(name).isPresent());
                case "limit" -> validLimit(value);
                default -> false;
            };
            if (!valid) return invalid(field, value);
        }
        return null;
    }

    private static boolean validNames(JsonElement value, java.util.function.Predicate<String> accepted) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > MAX_EXPOSED) return false;
        var unique = new HashSet<String>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) return false;
            String name = item.getAsString();
            if (name.isBlank() || !unique.add(name) || !accepted.test(name)) return false;
        }
        return true;
    }

    private static boolean validLimit(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try {
            int limit = value.getAsBigDecimal().intValueExact();
            return limit >= 1 && limit <= MAX_EXPOSED;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return false;
        }
    }

    private static JsonObject invalid(String field, JsonElement value) {
        var error = new JsonObject();
        error.addProperty("code", "invalid_discovery_selection");
        error.addProperty("error", DiscoveryMessages.text("invalid", field));
        error.addProperty("field", field);
        error.add("invalid_value", value.deepCopy());
        return error;
    }
}
