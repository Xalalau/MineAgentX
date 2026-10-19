package com.mineagent.tools.construction;

import com.google.gson.*;
import com.mineagent.api.construction.ConstructionDesign;
import java.math.BigDecimal;
import java.util.Set;

/** Reject fractional and coerced JSON integers before Gson record conversion. */
final class ConstructionCodec {
    static final Gson JSON = new Gson();
    private ConstructionCodec() {}
    static ConstructionDesign design(JsonObject object) {
        if (object == null) throw new IllegalArgumentException("design");
        require(object, "id", "dimension", "origin", "quarterTurns", "size", "decisions", "cells");
        integer(object, "quarterTurns", 0, 3, -1);
        point(object.getAsJsonObject("origin")); point(object.getAsJsonObject("size"));
        JsonObject decisions = object.getAsJsonObject("decisions");
        require(decisions, "purpose", "interiors", "access", "terrain", "materials", "transport");
        for (String key : decisions.keySet()) string(decisions, key, null);
        var cells = object.getAsJsonArray("cells");
        if (cells == null || cells.size() < 1 || cells.size() > ConstructionDesign.MAX_CELLS) throw new IllegalArgumentException("cells");
        for (var cell : cells) {
            var c = cell.getAsJsonObject(); require(c, "position", "block");
            point(c.getAsJsonObject("position")); string(c, "block", null);
        }
        string(object, "id", null); string(object, "dimension", null);
        return JSON.fromJson(object, ConstructionDesign.class);
    }
    static void require(JsonObject object, String... keys) {
        if (object == null || !object.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("object_fields");
        for (String key : keys) if (object.get(key).isJsonNull()) throw new IllegalArgumentException("null_field");
    }
    private static void point(JsonObject object) {
        require(object, "x", "y", "z");
        for (String key : new String[]{"x", "y", "z"}) integer(object, key, Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
    }
    static int integer(JsonObject object, String key, int min, int max, int fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("integer");
        int number;
        try { number = new BigDecimal(element.getAsString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException error) { throw new IllegalArgumentException("integer", error); }
        if (number < min || number > max) throw new IllegalArgumentException("integer_bounds");
        return number;
    }
    static String string(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            if (fallback == null) throw new IllegalArgumentException("string");
            return fallback;
        }
        JsonElement e = object.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("string");
        return e.getAsString();
    }
    static boolean bool(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return false;
        var e = object.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("boolean");
        return e.getAsBoolean();
    }
}
