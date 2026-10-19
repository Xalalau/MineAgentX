package com.mineagent.engine.llm;

import com.mineagent.api.i18n.LocalizedValidationException;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.provider.LLMProviderException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.net.URI;

/** Shared validation for the HTTP-backed LLM providers. */
final class ProviderSupport {

    private ProviderSupport() {}

    /** Reject invalid announced calls rather than accepting a partial or empty tool turn. */
    static JsonArray validatedToolCalls(JsonObject message) {
        JsonElement calls = message.get("tool_calls");
        if (calls == null || calls.isJsonNull()) return new JsonArray();
        if (!calls.isJsonArray()) {
            throw new IllegalArgumentException("tool_calls must be an array");
        }
        for (JsonElement item : calls.getAsJsonArray()) {
            if (!item.isJsonObject() || !item.getAsJsonObject().has("function")
                    || !item.getAsJsonObject().get("function").isJsonObject()) {
                throw new IllegalArgumentException("tool call must contain a function object");
            }
            JsonElement name = item.getAsJsonObject().getAsJsonObject("function").get("name");
            if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                    || name.getAsString().isBlank()) {
                throw new IllegalArgumentException("tool function name must be a nonblank string");
            }
        }
        return calls.getAsJsonArray();
    }

    /** Preserve valid server IDs exactly and reject ambiguous result correlation. */
    static String toolCallId(JsonObject call, java.util.Set<String> seenIds, String prefix) {
        JsonElement supplied = call.get("id");
        if (supplied == null || supplied.isJsonNull()) {
            String generated;
            do { generated = prefix + java.util.UUID.randomUUID(); }
            while (!seenIds.add(generated));
            return generated;
        }
        if (!supplied.isJsonPrimitive() || !supplied.getAsJsonPrimitive().isString()
                || supplied.getAsString().isBlank() || !seenIds.add(supplied.getAsString())) {
            throw new IllegalArgumentException("tool call IDs must be nonblank unique strings");
        }
        return supplied.getAsString();
    }

    static String validatedBaseUrl(String configured, String fallback,
                                   String provider) {
        String base = configured == null || configured.isBlank()
                ? fallback : configured.trim();
        if (base == null || base.isBlank()) {
            throw new LocalizedValidationException("validation.url_missing", provider + " base URL is not configured", null, provider);
        }
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException malformed) {
            throw new LocalizedValidationException("validation.url_malformed", provider + " base URL is malformed", malformed, provider);
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new LocalizedValidationException("validation.url_shape", provider
                    + " base URL must be an absolute HTTP(S) URL without query or fragment", null, provider);
        }
        // Only loopback, private LAN, link-local, and IPv6 unique-local hosts
        // are allowed. Check every DNS answer rather than accepting a public
        // address hidden behind a friendly local-looking hostname.
        try {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
                if (address.isAnyLocalAddress() || address.isMulticastAddress()
                        || !(address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || uniqueLocal)) {
                    throw new LocalizedValidationException("validation.url_public", provider
                            + " requires a loopback or private LAN server; public endpoints are disabled", null, provider);
                }
            }
        } catch (java.net.UnknownHostException unresolved) {
            throw new LocalizedValidationException("validation.url_unresolved", provider + " local server hostname cannot be resolved", unresolved, provider);
        }
        // Callers append a fixed API suffix; trimming avoids a double slash
        // that strict reverse proxies may reject.
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** Append an API path without duplicating a user-supplied /v1 segment. */
    static String endpoint(String base, String apiPath) {
        // Some relay dashboards expose a complete endpoint while others expose
        // only the host or version root. Accept all three forms without ever
        // duplicating /v1/chat/completions or /v1/messages.
        if (base.endsWith(apiPath)) return base;
        int nextSlash = apiPath.indexOf('/', 1);
        String versionPrefix = nextSlash > 0
                ? apiPath.substring(0, nextSlash) : apiPath;
        return base.endsWith(versionPrefix)
                ? base + apiPath.substring(versionPrefix.length())
                : base + apiPath;
    }

    static void validateRequest(String provider, String apiKey, String model,
                                List<ChatMessage> messages, double temperature,
                                int maxTokens) {
        validateRequest(provider, apiKey, model, messages, temperature,
                maxTokens, true);
    }

    static void validateRequest(String provider, String apiKey, String model,
                                List<ChatMessage> messages, double temperature,
                                int maxTokens, boolean apiKeyRequired) {
        validateRequest(provider, apiKey, model, messages, temperature,
                maxTokens, apiKeyRequired, false);
    }

    static void validateRequest(String provider, String apiKey, String model,
                                List<ChatMessage> messages, double temperature,
                                int maxTokens, boolean apiKeyRequired,
                                boolean allowEmptyMessages) {
        if (apiKeyRequired && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException(provider + " API key is not configured");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(provider + " model is not configured");
        }
        if (!allowEmptyMessages && (messages == null || messages.isEmpty())) {
            throw new IllegalArgumentException(provider + " request has no messages");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException(provider + " maxTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException(
                    provider + " temperature must be finite and between 0 and 2");
        }
    }

    static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    /**
     * Convert persisted tool arguments into Ollama's native object shape.
     * A malformed historical argument must not poison
     * every later request; the tool result already records the original
     * failure, so an empty object is the only safe replay representation.
     */
    static JsonObject toolArgumentsObject(String arguments) {
        if (arguments == null || arguments.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    /** A successful HTTP response with a missing/truncated JSON envelope is transient. */
    static LLMProviderException malformedResponse(String provider, String body,
                                                   RuntimeException cause) {
        return new LLMProviderException(provider + " API returned a malformed response: "
                + truncate(body, 500), null, true, cause);
    }
}
