package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonParser;
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

final class ExtendedNativeProviderTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void ollamaNativeUsesSingleJsonAndParsesToolCall() throws IOException {
        AtomicReference<String> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"goto\",\"arguments\":{\"x\":1}}}]},\"done\":true,\"done_reason\":\"stop\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        LLMResponse response = new OllamaNativeProvider().complete(
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "llama3.2",
                List.of(ChatMessage.user("go")), List.of(), 0.0, 128, null);
        assertEquals("goto", response.choice().message().toolCalls().get(0).name());
        assertEquals("{\"x\":1}", response.choice().message().toolCalls().get(0).arguments());
        // Gson may emit compact or pretty JSON depending on the shared
        // provider serializer.  Assert the protocol fields structurally so a
        // harmless whitespace change cannot hide a regression in the wire
        // contract.
        var requestJson = JsonParser.parseString(request.get()).getAsJsonObject();
        assertFalse(requestJson.get("stream").getAsBoolean());
        assertEquals(128, requestJson.getAsJsonObject("options").get("num_predict").getAsInt());
    }

    @Test
    void cohereParsesContentBlocksAndToolCalls() {
        LLMResponse response = new CohereProvider().parse("""
                {"id":"cohere-1","finish_reason":"TOOL_CALL",
                 "message":{"content":[{"type":"text","text":"checking"}],
                 "tool_calls":[{"id":"tc-1","function":{"name":"goto","arguments":{"x":1}}}]},
                 "usage":{"tokens":{"input_tokens":11,"output_tokens":4}}}
                """, "command-a-03-2025");
        assertEquals("checking", response.choice().message().content());
        assertEquals("goto", response.choice().message().toolCalls().get(0).name());
        assertEquals("{\"x\":1}", response.choice().message().toolCalls().get(0).arguments());
        assertEquals(11, response.usage().promptTokens());
    }

    @Test
    void azureUsesDeploymentRouteAndApiKeyHeader() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openai/deployments/", exchange -> {
            path.set(exchange.getRequestURI().toString()); key.set(exchange.getRequestHeaders().getFirst("api-key"));
            byte[] body = "{\"id\":\"az-1\",\"model\":\"deployment\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        String credentials = "{\"apiKey\":\"azure-secret\",\"apiVersion\":\"2024-10-21\",\"deployment\":\"deployment\"}";
        LLMResponse response = new AzureOpenAIProvider().complete("http://127.0.0.1:" + server.getAddress().getPort(), credentials,
                "ignored", List.of(ChatMessage.user("hi")), List.of(), 0.0, 32, null);
        assertEquals("ok", response.choice().message().content());
        assertTrue(path.get().contains("/openai/deployments/deployment/chat/completions?api-version=2024-10-21"));
        assertEquals("azure-secret", key.get());
    }

    @Test
    void azureBearerCredentialUsesAuthorizationHeader() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/openai/deployments/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            byte[] body = "{\"id\":\"az-2\",\"model\":\"deployment\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        new AzureOpenAIProvider().complete("http://127.0.0.1:" + server.getAddress().getPort(),
                "bearer:entra-token", "deployment", List.of(ChatMessage.user("hi")), List.of(), 0.0, 32, null);
        assertEquals("Bearer entra-token", authorization.get());
        assertNull(apiKey.get());
    }

    @Test
    void tencentTc3SignatureIsStable() {
        var credentials = TencentHunyuanProvider.Credentials.parse(
                "{\"secretId\":\"id\",\"secretKey\":\"key\",\"region\":\"ap-guangzhou\"}");
        var one = TencentHunyuanProvider.sign("{}", credentials, 1735689600L);
        var two = TencentHunyuanProvider.sign("{}", credentials, 1735689600L);
        assertEquals(one.authorization(), two.authorization());
        assertTrue(one.authorization().startsWith("TC3-HMAC-SHA256 Credential=id/"));
    }

    @Test
    void bedrockInvokeProfilesParseCommonOutputs() {
        LLMResponse anthropic = BedrockInvokeModelProvider.parse(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ready\"}],\"stop_reason\":\"end_turn\"}",
                "anthropic.claude-3-5-sonnet", BedrockInvokeModelProvider.Profile.ANTHROPIC);
        LLMResponse llama = BedrockInvokeModelProvider.parse(
                "{\"generation\":\"ready\"}", "meta.llama3", BedrockInvokeModelProvider.Profile.LLAMA);
        assertEquals("ready", anthropic.choice().message().content());
        assertEquals("ready", llama.choice().message().content());
    }
}
