package com.mineagent.engine.identity;

import com.google.gson.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Loader-independent implementation behind the native tool; cannot synthesize observation evidence. */
public final class IdentityMemoryAccess {
    private static final Gson GSON = new Gson();
    private final EvolvingIdentity identity;
    private final IdentityReflection reflection;
    private final LongSupplier generation;

    public IdentityMemoryAccess(EvolvingIdentity identity, IdentityReflection reflection, LongSupplier generation) {
        this.identity = Objects.requireNonNull(identity);
        this.reflection = Objects.requireNonNull(reflection);
        this.generation = Objects.requireNonNull(generation);
    }

    public String execute(JsonObject args, String language) {
        try {
            String operation = string(args, "operation", "recall");
            if (operation.equals("revise")) {
                if (!args.has("evidence_ids") || !args.get("evidence_ids").isJsonArray()) throw new IllegalArgumentException("invalid_evidence_ids");
                List<String> ids = new ArrayList<>();
                for (JsonElement id : args.getAsJsonArray("evidence_ids")) {
                    if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid_evidence_id");
                    ids.add(id.getAsString());
                }
                var entry = reflection.revise(generation.getAsLong(), string(args, "kind", ""),
                        string(args, "subject", ""), string(args, "statement", ""), string(args, "reason", ""),
                        ids, number(args, "confidence").doubleValue(), number(args, "expected_revision").longValueExact(),
                        string(args, "reconsideration", ""));
                JsonObject result = new JsonObject();
                result.addProperty("status", "updated"); result.addProperty("revision", entry.revision());
                result.addProperty("message", IdentityText.text(language, "Evidence-backed identity updated.", "Identidade atualizada com base em evidências."));
                return result.toString();
            }
            if (!operation.equals("recall")) throw new IllegalArgumentException("unsupported_operation");
            String query = string(args, "query", "").toLowerCase(Locale.ROOT);
            String subject = string(args, "subject", "");
            if (query.length() > 300 || subject.length() > 120) throw new IllegalArgumentException("invalid_query");
            List<String> selectedIds = new ArrayList<>();
            if (args.has("evidence_ids")) {
                if (!args.get("evidence_ids").isJsonArray()) throw new IllegalArgumentException("invalid_evidence_ids");
                for (JsonElement id : args.getAsJsonArray("evidence_ids")) {
                    if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid_evidence_id");
                    selectedIds.add(id.getAsString());
                }
                if (selectedIds.isEmpty() || selectedIds.size() > 3) throw new IllegalArgumentException("invalid_evidence_ids");
            }
            JsonObject result = new JsonObject();
            JsonArray evidence = new JsonArray();
            var observations = new ArrayList<>(identity.evidence());
            Collections.reverse(observations);
            for (var item : observations) {
                if (evidence.size() >= 4) break;
                if (!selectedIds.isEmpty() && !selectedIds.contains(item.id())) continue;
                if (selectedIds.isEmpty() && !query.isBlank() && !matches(query, item.text() + " " + item.source())) continue;
                JsonObject value = GSON.toJsonTree(item).getAsJsonObject();
                value.addProperty("provenance", IdentityText.text(language,
                        item.origin().equals("observation") ? "server observation" : "player report, not verified",
                        item.origin().equals("observation") ? "observação do servidor" : "relato do jogador, não verificado"));
                evidence.add(value);
            }
            result.add("evidence", evidence);
            JsonArray entries = new JsonArray();
            result.add("entries", entries);
            for (var entry : identity.entries()) {
                if (entries.size() >= 3) break;
                if (!subject.isBlank() && !subject.equals(entry.subject())) continue;
                if (!query.isBlank() && !matches(query, entry.subject() + " " + entry.statement() + " " + entry.reason())) continue;
                JsonObject value = GSON.toJsonTree(entry).getAsJsonObject();
                if (subject.isBlank()) value.remove("history");
                entries.add(value);
                if (result.toString().length() > 5800) entries.remove(entries.size() - 1);
            }
            result.addProperty("message", IdentityText.text(language,
                    "Relevant stored evidence only. Use an exact subject to retrieve revision history. Player reports remain unverified.",
                    "Apenas evidências armazenadas relevantes. Use o assunto exato para recuperar o histórico de revisões. Relatos do jogador continuam não verificados."));
            return result.toString();
        } catch (RuntimeException invalid) {
            JsonObject error = new JsonObject();
            String code = invalid.getMessage();
            var known = java.util.Set.of("reflection_closed_or_stale", "unsupported_operation",
                    "invalid_or_unsupported_revision", "revision_needs_new_evidence", "unknown_evidence_id",
                    "stale_revision", "identity_capacity", "missing_operation", "missing_subject", "missing_statement",
                    "missing_reason", "missing_evidence_ids", "missing_confidence", "missing_expected_revision");
            error.addProperty("error", "invalid_identity_request");
            error.addProperty("code", code != null && known.contains(code) ? code : "invalid_identity_request");
            error.addProperty("message", IdentityText.text(language, "Identity request rejected; no observation was invented or changed.",
                    "Pedido de identidade rejeitado; nenhuma observação foi inventada nem alterada."));
            return error.toString();
        }
    }

    private static java.math.BigDecimal number(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("invalid_number");
        }
        return args.get(key).getAsBigDecimal();
    }
    private static boolean matches(String query, String content) {
        String text = content.toLowerCase(Locale.ROOT);
        return Arrays.stream(query.split("[^\\p{L}\\p{N}_]+"))
                .filter(word -> word.length() > 2).anyMatch(text::contains);
    }
    private static String string(JsonObject args, String key, String fallback) {
        if (args == null) throw new IllegalArgumentException("missing_arguments");
        if (!args.has(key)) return fallback;
        JsonElement value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid_string");
        return value.getAsString();
    }
}
