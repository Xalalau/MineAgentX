package com.mineagent.api.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strictly replaces schema prose using explicit RFC 6901 pointers, never technical values. */
public final class ToolSchemaLocalizer {
    private static final Set<String> LITERAL_DATA = Set.of("enum", "const", "default", "examples");
    private static final Set<String> SCHEMA_MAPS = Set.of(
            "properties", "patternProperties", "$defs", "definitions", "dependentSchemas");

    private ToolSchemaLocalizer() {}

    /** All description/title strings need catalog keys; stale or technical-field pointers fail. */
    public static JsonObject localize(Language language, JsonObject definition,
                                      Map<String, String> textKeys, MessageCatalog catalog) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(textKeys, "textKeys");
        Objects.requireNonNull(catalog, "catalog");
        JsonObject copy = Objects.requireNonNull(definition, "definition").deepCopy();
        Set<String> used = new HashSet<>();
        visit(language, copy, "", textKeys, catalog, used, false);
        if (!used.equals(textKeys.keySet())) {
            throw new IllegalArgumentException("Locale pointers include absent or technical fields");
        }
        return copy;
    }

    private static void visit(Language language, JsonElement element, String pointer,
                               Map<String, String> textKeys, MessageCatalog catalog, Set<String> used, boolean schemaMap) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (var entry : object.entrySet()) {
                String name = entry.getKey();
                String childPointer = pointer + "/" + name.replace("~", "~0").replace("/", "~1");
                JsonElement value = entry.getValue();
                if (!schemaMap && (name.equals("description") || name.equals("title"))
                        && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String key = textKeys.get(childPointer);
                    if (key == null) throw new IllegalArgumentException("Missing locale pointer: " + childPointer);
                    object.addProperty(name, catalog.text(language, key));
                    used.add(childPointer);
                } else if (schemaMap || !LITERAL_DATA.contains(name)) {
                    visit(language, value, childPointer, textKeys, catalog, used,
                            !schemaMap && SCHEMA_MAPS.contains(name));
                }
            }
        } else if (element.isJsonArray()) {
            for (int i = 0; i < element.getAsJsonArray().size(); i++) {
                visit(language, element.getAsJsonArray().get(i), pointer + "/" + i, textKeys, catalog, used, false);
            }
        }
    }
}
