package com.mineagent.engine.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.mineagent.api.llm.ChatMessage;

import java.util.List;

/** Native Ollama message and argument serialization. */
final class NativeProviderSupport {
    static final Gson GSON = new GsonBuilder().create();
    private NativeProviderSupport() {}

    static JsonArray messages(List<ChatMessage> messages) {
        JsonArray array = new JsonArray();
        if (messages == null) return array;
        for (ChatMessage message : messages) {
            if (message == null) continue;
            JsonObject object = new JsonObject();
            object.addProperty("role", message.role());
            object.addProperty("content", message.content() == null ? "" : message.content());
            if (!message.images().isEmpty()) {
                JsonArray images = new JsonArray();
                for (String image : message.images()) images.add(image.substring(image.indexOf(',') + 1));
                object.add("images", images);
            }
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

}
