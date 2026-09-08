package com.mineagent.engine.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared transport and wire helpers for native vendor adapters. */
final class NativeProviderSupport {
    static final Gson GSON = new GsonBuilder().create();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    private NativeProviderSupport() {}

    static Duration timeout(Duration deadline) {
        if (deadline == null || deadline.isNegative() || deadline.isZero()) {
            return Duration.ofSeconds(30);
        }
        return deadline.compareTo(Duration.ofMinutes(10)) > 0
                ? Duration.ofMinutes(10) : deadline;
    }

    static HttpResponse<String> post(String provider, String url, String body,
                                     Map<String, String> headers, Duration deadline) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(timeout(deadline));
            // Host is derived from the request URI by java.net.http and is a
            // restricted header on some JDKs.  Protocol signers still include
            // it in their canonical request, but must not try to inject it as
            // an ordinary application header.
            if (headers != null) headers.forEach((name, value) -> {
                if (name != null && !"host".equalsIgnoreCase(name)) {
                    builder.header(name, value);
                }
            });
            HttpResponse<String> response = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LLMProviderException.http(provider, response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
            }
            return response;
        } catch (LLMProviderException failure) {
            throw failure;
        } catch (java.io.IOException failure) {
            throw LLMProviderException.transport(provider, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException(provider + " API call interrupted", null, false,
                    interrupted);
        } catch (RuntimeException malformed) {
            throw malformed;
        }
    }

    static JsonArray messages(List<ChatMessage> messages) {
        JsonArray array = new JsonArray();
        if (messages == null) return array;
        for (ChatMessage message : messages) {
            if (message == null) continue;
            JsonObject object = new JsonObject();
            object.addProperty("role", message.role());
            object.addProperty("content", message.content() == null ? "" : message.content());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                    if (call == null || call.name() == null) continue;
                    JsonObject item = new JsonObject();
                    item.addProperty("id", call.id());
                    item.addProperty("type", "function");
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.add("arguments", ProviderSupport.toolArgumentsObject(call.arguments()));
                    item.add("function", function);
                    calls.add(item);
                }
                if (!calls.isEmpty()) object.add("tool_calls", calls);
            }
            if (message.toolCallId() != null) object.addProperty("tool_call_id", message.toolCallId());
            array.add(object);
        }
        return array;
    }

    static JsonArray tools(List<Map<String, Object>> tools) {
        JsonArray array = new JsonArray();
        if (tools != null) for (Map<String, Object> tool : tools) {
            if (tool != null) array.add(GSON.toJsonTree(tool));
        }
        return array;
    }

    /**
     * Normalize a vendor tool-call argument value to the unified JSON string
     * representation.  Several native APIs are inconsistent here: some send
     * {@code arguments} as an object while others send a JSON string.  Using
     * {@link Gson#toJson(JsonElement)} blindly on the latter adds a second
     * layer of quotes and makes the tool registry see a string instead of an
     * argument object.
     */
    static String toolArguments(JsonElement value) {
        if (value == null || value.isJsonNull()) return "{}";
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String raw = value.getAsString();
            if (raw == null || raw.isBlank()) return "{}";
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                return parsed.isJsonObject() || parsed.isJsonArray()
                        ? GSON.toJson(parsed) : GSON.toJson(value);
            } catch (RuntimeException ignored) {
                // Keep malformed provider output visible to the normal tool
                // argument validator instead of silently changing its value.
                return GSON.toJson(value);
            }
        }
        return GSON.toJson(value);
    }

    static String endpoint(String configured, String fallback, String suffix, String provider) {
        String base = ProviderSupport.validatedBaseUrl(configured, fallback, provider);
        return ProviderSupport.endpoint(base, suffix);
    }
}
