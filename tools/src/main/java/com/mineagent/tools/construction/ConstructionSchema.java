package com.mineagent.tools.construction;

import java.util.*;

/** Complete structured syntax is separate from the architectural decision guidance. */
final class ConstructionSchema {
    private ConstructionSchema() {}
    static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
    }
    private static Map<String, Object> integer(int min, int max) { return Map.of("type", "integer", "minimum", min, "maximum", max); }
    private static Map<String, Object> string(int max) { return Map.of("type", "string", "minLength", 1, "maxLength", max); }
    private static Map<String, Object> point(int min, int max) { return object(Map.of("x", integer(min, max), "y", integer(min, max), "z", integer(min, max)), "x", "y", "z"); }
    static Map<String, Object> designTool(String l) {
        var decisions = new LinkedHashMap<String, Object>();
        for (String field : List.of("purpose", "interiors", "access", "terrain", "materials", "transport")) decisions.put(field, string(1024));
        var design = object(Map.of("id", Map.of("type", "string", "pattern", "[a-z0-9][a-z0-9_-]{0,47}"),
                "dimension", string(128), "origin", point(-30000000, 30000000), "quarterTurns", integer(0, 3),
                "size", point(1, 64), "decisions", object(decisions, "purpose", "interiors", "access", "terrain", "materials", "transport"),
                "cells", Map.of("type", "array", "minItems", 1, "maxItems", 4096, "items",
                        object(Map.of("position", point(0, 63), "block", string(128)), "position", "block"))),
                "id", "dimension", "origin", "quarterTurns", "size", "decisions", "cells");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("mode", Map.of("type", "string", "enum", List.of("help", "list", "read", "revise", "assess", "preview", "review", "review_status", "observe", "evidence"),
                "description", ConstructionText.text(l, "schema_mode")));
        properties.put("id", string(48)); properties.put("revision", integer(0, 128));
        properties.put("expected_revision", integer(0, 128)); properties.put("reason", string(1024));
        properties.put("design", design); properties.put("after", Map.of("type", "string", "maxLength", 48));
        properties.put("offset", integer(0, 4096)); properties.put("limit", integer(1, 128));
        properties.put("candidates", Map.of("type", "array", "minItems", 1, "maxItems", 4, "items",
                object(Map.of("dimension", string(128), "origin", point(-30000000, 30000000), "quarterTurns", integer(0, 3)), "dimension", "origin", "quarterTurns")));
        return object(properties, "mode");
    }
    static Map<String, Object> executeTool(String l) {
        return object(Map.of("id", string(48), "revision", integer(1, 128),
                "allow_clear", Map.of("type", "boolean", "description", ConstructionText.text(l, "schema_clear"))), "id", "revision");
    }
}
