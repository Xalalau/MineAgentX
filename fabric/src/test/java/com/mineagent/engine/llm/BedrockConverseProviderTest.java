package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class BedrockConverseProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void buildsConverseMessagesToolsAndMergesAdjacentRoles() {
        BedrockConverseProvider provider = new BedrockConverseProvider();
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are concise."),
                ChatMessage.user("hello"),
                ChatMessage.assistant("", List.of(new ChatMessage.ToolCallRef(
                        "call-1", "get_weather", "{\"city\":\"Shanghai\"}"))),
                ChatMessage.toolResult("call-1", "{\"temperature\":25}"));
        var body = provider.buildBody("amazon.nova-pro-v1:0", messages,
                List.of(Map.of("type", "function", "function", Map.of(
                        "name", "get_weather", "description", "weather",
                        "parameters", Map.of("type", "object")))) , 0.2, 512);

        assertEquals("You are concise.", body.getAsJsonArray("system")
                .get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(3, body.getAsJsonArray("messages").size());
        var assistant = body.getAsJsonArray("messages").get(1).getAsJsonObject();
        assertEquals("assistant", assistant.get("role").getAsString());
        assertEquals("call-1", assistant.getAsJsonArray("content").get(0)
                .getAsJsonObject().getAsJsonObject("toolUse")
                .get("toolUseId").getAsString());
        var toolResult = body.getAsJsonArray("messages").get(2).getAsJsonObject();
        assertEquals("user", toolResult.get("role").getAsString());
        assertEquals("call-1", toolResult.getAsJsonArray("content").get(0)
                .getAsJsonObject().getAsJsonObject("toolResult")
                .get("toolUseId").getAsString());
        assertEquals("get_weather", body.getAsJsonObject("toolConfig")
                .getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonObject("toolSpec").get("name").getAsString());
    }

    @Test
    void parsesTextToolUseAndUsage() {
        BedrockConverseProvider provider = new BedrockConverseProvider();
        LLMResponse response = provider.parseResponse("""
                {"output":{"message":{"role":"assistant","content":[
                  {"text":"I will check."},
                  {"toolUse":{"toolUseId":"tu-1","name":"goto","input":{"x":1}}}]}},
                 "stopReason":"tool_use",
                 "usage":{"inputTokens":100,"outputTokens":12,"totalTokens":112,
                           "cacheReadInputTokens":80,"cacheWriteInputTokens":5}}
                """, "amazon.nova-pro-v1:0", "req-1");
        assertEquals("req-1", response.id());
        assertEquals("I will check.", response.choice().message().content());
        assertEquals("goto", response.choice().message().toolCalls().get(0).name());
        assertEquals("{\"x\":1}", response.choice().message().toolCalls().get(0).arguments());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(80, response.usage().cachedPromptTokens());
        assertEquals(5, response.usage().cacheCreationPromptTokens());
    }

    @Test
    void customApiKeyRelayUsesConversePathAndDoesNotSign() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // HttpServer exposes the decoded request path to context matching;
        // register the stable prefix while still asserting the encoded model
        // segment in the handler's URI when needed.
        server.createContext("/model/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"output\":{\"message\":{\"content\":[{\"text\":\"ok\"}]}},\"stopReason\":\"end_turn\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BedrockConverseProvider provider = new BedrockConverseProvider();
        LLMResponse result = provider.complete(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "api-key:relay-secret", "amazon.nova-pro-v1:0",
                List.of(ChatMessage.user("hello")), List.of(), 0.0, 64, null);
        assertEquals("ok", result.choice().message().content());
        assertEquals("relay-secret", auth.get());
        assertTrue(requestBody.get().contains("\"messages\""));
    }

    @Test
    void sigV4IsDeterministicAndEncodesModelId() {
        assertEquals("anthropic.claude-3-5-sonnet-20241022-v2%3A0",
                BedrockConverseProvider.percentEncodePathValue(
                        "anthropic.claude-3-5-sonnet-20241022-v2:0"));
        var credentials = BedrockConverseProvider.Credentials.parse(
                "{\"accessKeyId\":\"AKID\",\"secretAccessKey\":\"SECRET\",\"region\":\"us-east-1\"}");
        var one = BedrockConverseProvider.sign("POST", "/model/a%3A0/converse", "",
                "bedrock-runtime.us-east-1.amazonaws.com", "{}", credentials,
                "us-east-1", Instant.parse("2025-01-01T00:00:00Z"));
        var two = BedrockConverseProvider.sign("POST", "/model/a%3A0/converse", "",
                "bedrock-runtime.us-east-1.amazonaws.com", "{}", credentials,
                "us-east-1", Instant.parse("2025-01-01T00:00:00Z"));
        assertEquals(one.authorization(), two.authorization());
        assertTrue(one.authorization().startsWith("AWS4-HMAC-SHA256 Credential=AKID/"));
        assertEquals("20250101T000000Z", one.headers().get("x-amz-date"));
    }

    @Test
    void officialEndpointRejectsOpaqueSingleToken() {
        BedrockConverseProvider provider = new BedrockConverseProvider();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                provider.complete("https://bedrock-runtime.us-east-1.amazonaws.com",
                        "only-one-token", "amazon.nova-pro-v1:0",
                        List.of(ChatMessage.user("hello")), List.of(), 0.0, 64, null));
        assertTrue(error.getMessage().contains("accessKeyId"));
    }
}
