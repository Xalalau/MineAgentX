package com.mineagent.engine.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.ChatMessage;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exercises the actual native Ollama transport against a loopback fixture server. */
public final class LocalOllamaProviderTest {
    public static void main(String[] args) throws Exception {
        preservesNativeToolCallRoundTrip();
        rejectsIncompleteResponses();
        assignsUniqueMissingIds();
        LocalBonsaiProviderTest.check(new OllamaNativeProvider().defaultModels().isEmpty(),
                "Ollama must not claim an unverified model is installed");
        System.out.println("LocalOllamaProviderTest: all checks passed");
    }

    static void preservesNativeToolCallRoundTrip() throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            String reply = requests.size() == 1 ? """
                    {"model":"configured-local-model","done":true,"message":{"role":"assistant","content":"",
                    "thinking":"Internal reasoning, not a command.","tool_calls":[{"function":{"name":"inspect_inventory","arguments":{"slot":1}}}]},
                    "prompt_eval_count":10,"eval_count":4}
                    """ : """
                    {"model":"configured-local-model","done":true,"done_reason":"stop",
                    "message":{"role":"assistant","content":"Done."}}
                    """;
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = new OllamaNativeProvider();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var tools = List.<Map<String, Object>>of(Map.of("type", "function", "function",
                    Map.of("name", "inspect_inventory", "parameters", Map.of("type", "object"))));
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("Check inventory.")));
            var result = provider.complete(base, "", "configured-local-model", messages, tools, 0.7, 4096, "auto");
            var call = result.choice().message().toolCalls().getFirst();
            check("inspect_inventory".equals(call.name()), "parse native function names");
            check("{\"slot\":1}".equals(call.arguments()), "normalize native object arguments");
            check(call.id() != null && !call.id().isBlank(), "assign a stable internal ID when Ollama omits IDs");
            check("tool_calls".equals(result.finishReason()), "native tools must reach the agent tool lifecycle");
            check(result.usage().promptTokens() == 10 && result.usage().completionTokens() == 4, "parse native usage");
            messages.add(result.choice().message());
            messages.add(ChatMessage.toolResult(call.id(), "One stone block."));
            var next = provider.complete(base + "/api/chat", "", "configured-local-model", messages, tools, 0.7, 4096, null);
            var body = requests.get(1);
            check(!body.get("stream").getAsBoolean(), "disable Ollama's default NDJSON stream");
            check(body.getAsJsonObject("options").get("num_predict").getAsInt() == 4096, "map output limit to num_predict");
            check(!body.has("max_tokens") && !body.has("think") && !body.has("reasoning_effort"), "preserve native and launcher defaults");
            var replay = body.getAsJsonArray("messages");
            check(replay.get(1).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                    .getAsJsonObject("function").get("arguments").isJsonObject(), "native replay arguments must be an object, not a JSON string");
            check("inspect_inventory".equals(replay.get(2).getAsJsonObject().get("tool_name").getAsString()),
                    "native tool results must include the correlated function name");
            check(call.id().equals(replay.get(2).getAsJsonObject().get("tool_call_id").getAsString()), "preserve internal result correlation");
            check("Done.".equals(next.choice().message().content()), "complete native tool-result turn");
        } finally { server.stop(0); }
    }

    static void rejectsIncompleteResponses() {
        var provider = new OllamaNativeProvider();
        for (String json : List.of("{\"done\":true}", "{\"done\":false,\"message\":{\"content\":\"Partial\"}}")) {
            boolean rejected = false;
            try { provider.parseResponse(json); }
            catch (RuntimeException expected) { rejected = true; }
            check(rejected, "incomplete native response must not become an empty successful turn");
        }
    }

    static void assignsUniqueMissingIds() {
        String json = """
                {"done":true,"message":{"role":"assistant","tool_calls":[{"function":{"name":"inspect_inventory","arguments":{}}}]}}
                """;
        var provider = new OllamaNativeProvider();
        String first = provider.parseResponse(json).choice().message().toolCalls().getFirst().id();
        String next = provider.parseResponse(json).choice().message().toolCalls().getFirst().id();
        check(!first.equals(next), "missing native IDs must not collide across conversation turns");
        String explicit = json.replace("{\"function\":", "{\"id\":\"server_exact_1\",\"function\":");
        check("server_exact_1".equals(provider.parseResponse(explicit).choice().message().toolCalls().getFirst().id()),
                "preserve native server IDs exactly when supplied");
    }

    private static void check(boolean condition, String message) { LocalBonsaiProviderTest.check(condition, message); }
}
