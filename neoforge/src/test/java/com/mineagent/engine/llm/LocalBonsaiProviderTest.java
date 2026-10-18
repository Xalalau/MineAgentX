package com.mineagent.engine.llm;

import com.mineagent.api.config.MineAgentConfig;

/** Standalone regression suite; also invoked by the JUnit bridge. */
public final class LocalBonsaiProviderTest {
    public static void main(String[] args) throws Exception {
        defaultsUseLocalBonsai();
        providerAllowsUnauthenticatedLocalServer();
        restrictsEndpointsToLocalNetwork();
        preservesNativeToolCallRoundTrip();
        exposesNoCloudFactories();
        modelHintsPreserveServerPolicy();
        System.out.println("LocalBonsaiProviderTest: all checks passed");
    }

    static void defaultsUseLocalBonsai() {
        var defaults = MineAgentConfig.LLMConfig.DEFAULTS;
        check("openai-compatible".equals(defaults.provider()), "default provider must be local OpenAI-compatible");
        check("http://127.0.0.1:8899/v1".equals(defaults.baseUrl()), "default endpoint must be the documented Bonsai server");
        check("swift-bonsai2-27b-katana-mtp".equals(defaults.model()), "default model must be the documented Katana alias");
        check(defaults.apiKey().isEmpty(), "local server must not require a key");
        check(defaults.maxTokens() == 4096, "leave room for reasoning and tool calls");
    }

    static void providerAllowsUnauthenticatedLocalServer() {
        var provider = new OpenAICompatibleProvider();
        check(!provider.requiresApiKey(), "local inference must allow an empty API key");
    }

    static void restrictsEndpointsToLocalNetwork() {
        String fallback = MineAgentConfig.LLMConfig.DEFAULTS.baseUrl();
        for (String base : java.util.List.of("http://127.0.0.1:8899/v1", "http://localhost:8899/v1",
                "http://192.168.1.20:8899/v1", "http://10.0.0.2:8899", "http://172.16.0.2:8899",
                "http://[::1]:8899/v1", "http://[fd12::1234]:8899/v1")) {
            check(base.equals(ProviderSupport.validatedBaseUrl(base, fallback, "openai-compatible")),
                    "private LAN and loopback endpoints must be configurable: " + base);
        }
        for (String base : java.util.List.of("http://8.8.8.8/v1", "https://1.1.1.1/v1",
                "http://0.0.0.0:8899/v1", "http://224.0.0.1/v1", "http://[2001:4860:4860::8888]/v1",
                "http://localhost:8899/v1?key=secret", "http://user:secret@localhost:8899/v1")) {
            boolean rejected = false;
            try { ProviderSupport.validatedBaseUrl(base, fallback, "openai-compatible"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "non-local or unsafe endpoint must be rejected: " + base);
        }
    }

    static void preservesNativeToolCallRoundTrip() throws Exception {
        var requests = new java.util.ArrayList<com.google.gson.JsonObject>();
        var authorization = new java.util.ArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String reply = requests.size() == 1
                    ? """
                      {"id":"local-1","model":"swift-bonsai2-27b-katana-mtp","choices":[{"finish_reason":"tool_calls","message":{
                      "role":"assistant","content":null,"reasoning_content":"Do not execute reasoning.",
                      "tool_calls":[{"id":"call_exact_123","type":"function","function":{"name":"inspect_inventory","arguments":"{\"\"slot\"\":1}"}}]}}]}
                      """.replace("\"\"", "\\\"")
                    : "{\"id\":\"local-2\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"}}]}";
            byte[] bytes = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var provider = new OpenAICompatibleProvider();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var messages = new java.util.ArrayList<com.mineagent.api.llm.ChatMessage>();
            messages.add(com.mineagent.api.llm.ChatMessage.user("Check inventory."));
            var tools = java.util.List.<java.util.Map<String, Object>>of(java.util.Map.of("type", "function", "function",
                    java.util.Map.of("name", "inspect_inventory", "parameters", java.util.Map.of("type", "object"))));
            var response = provider.complete(url, "", "swift-bonsai2-27b-katana-mtp", messages, tools, 0.7, 4096, "auto");
            var first = requests.getFirst();
            check(!first.has("reasoning_effort") && !first.has("chat_template_kwargs") && !first.has("thinking"),
                    "auto must preserve launcher reasoning/template defaults");
            check(!first.has("response_format"), "native tools must not become structured action JSON");
            check(authorization.getFirst() == null, "empty local key must omit Authorization");
            check(first.getAsJsonArray("tools").size() == 1, "send the real function schema");
            var call = response.choice().message().toolCalls().getFirst();
            check("call_exact_123".equals(call.id()), "preserve server tool-call IDs exactly");
            check("inspect_inventory".equals(call.name()), "preserve function names");
            check("{\"slot\":1}".equals(call.arguments()), "preserve valid function arguments");
            check(response.choice().message().content() == null, "never expose reasoning_content as assistant content");
            messages.add(response.choice().message());
            messages.add(com.mineagent.api.llm.ChatMessage.toolResult(call.id(), "One stone block."));
            var next = provider.complete(new com.mineagent.api.llm.LLMRequest(url, "", "swift-bonsai2-27b-katana-mtp",
                    messages, java.util.List.of(), tools, 0.7, 4096, "auto", null, null, "companion-key",
                    java.time.Duration.ofSeconds(180), false));
            var replay = requests.get(1).getAsJsonArray("messages");
            check("call_exact_123".equals(replay.get(1).getAsJsonObject().getAsJsonArray("tool_calls")
                    .get(0).getAsJsonObject().get("id").getAsString()), "assistant replay must keep call IDs");
            check("call_exact_123".equals(replay.get(2).getAsJsonObject().get("tool_call_id").getAsString()),
                    "tool result must correlate to the original call");
            check("Done.".equals(next.choice().message().content()), "complete the tool-result turn");
        } finally { server.stop(0); }
    }

    static void exposesNoCloudFactories() {
        for (var method : OpenAICompatibleProvider.class.getDeclaredMethods()) {
            check(!(java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == OpenAICompatibleProvider.class),
                    "vendor factories must be removed: " + method.getName());
        }
    }

    static void modelHintsPreserveServerPolicy() {
        var spec = com.mineagent.api.llm.model.ThinkingEffortSpec.forModel("swift-bonsai2-27b-katana-mtp");
        check(spec.supportedLevels().equals(java.util.List.of("auto")), "UI must only offer server-managed auto reasoning");
        check("auto".equals(spec.defaultLevel()), "server defaults must remain automatic");
        check(com.mineagent.api.llm.model.ModelFamilies.defaultMaxTokens("swift-bonsai2-27b-katana-mtp") == 4096,
                "local model fallback must leave 4096 output tokens");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
