package com.mineagent.engine.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Invalid announced tool calls must reach the transport's malformed-response path. */
final class LocalToolCallValidationTest {
    @Test void bonsaiRejectsMalformedToolCallStructures() {
        assertMalformedCallsRejected(new OpenAICompatibleProvider());
    }

    @Test void ollamaRejectsMalformedToolCallStructures() {
        assertMalformedCallsRejected(new OllamaNativeProvider());
    }

    @Test void bonsaiRejectsInvalidOrDuplicateExplicitIds() {
        assertInvalidIdsRejected(new OpenAICompatibleProvider());
    }

    @Test void ollamaRejectsInvalidOrDuplicateExplicitIds() {
        assertInvalidIdsRejected(new OllamaNativeProvider());
    }

    @Test void bothProtocolsPreserveValidIdsAndGenerateUniqueMissingIds() {
        for (var provider : List.of(new OpenAICompatibleProvider(), new OllamaNativeProvider())) {
            String fields = "\"tool_calls\":[" + call("\"server_exact_1\"") + ","
                    + call("\" server_exact_2 \"") + "," + call(null) + "," + call(null) + "]";
            var calls = provider.parseResponse(response(provider, fields)).choice().message().toolCalls();
            assertEquals("server_exact_1", calls.get(0).id());
            assertEquals(" server_exact_2 ", calls.get(1).id());
            assertTrue(calls.stream().allMatch(c -> c.id() != null && !c.id().isBlank()));
            assertEquals(4, calls.stream().map(c -> c.id()).distinct().count());
        }
    }

    @Test void bothProtocolsAllowTextAndReasoningOnlyTurnsWithoutToolCalls() {
        for (var provider : List.of(new OpenAICompatibleProvider(), new OllamaNativeProvider())) {
            for (String fields : List.of("\"content\":\"Done.\"", "\"content\":\"Done.\",\"tool_calls\":null",
                    "\"content\":\"Done.\",\"tool_calls\":[]", "\"reasoning_content\":\"Internal only\"",
                    "\"thinking\":\"Internal only\"")) {
                assertNull(provider.parseResponse(response(provider, fields)).choice().message().toolCalls());
            }
        }
    }

    @Test void malformedToolTurnsBecomeRetryableTransportFailures() throws Exception {
        for (var provider : List.of(new OpenAICompatibleProvider(), new OllamaNativeProvider())) {
            for (String calls : List.of("[42]", "[" + call("\"\"") + "]",
                    "[" + call("\"duplicate\"") + "," + call("\"duplicate\"") + "]")) {
                var server = com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = response(provider, "\"tool_calls\":" + calls)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.start();
                try {
                    String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
                    var failure = assertThrows(com.mineagent.api.llm.provider.LLMProviderException.class,
                            () -> provider.complete(endpoint, "", "fixture-model",
                                    List.of(com.mineagent.api.llm.ChatMessage.user("Check inventory.")),
                                    List.of(), 0.7, 128, "auto"));
                    assertTrue(failure.retryable());
                    assertTrue(failure.getMessage().contains("malformed response"));
                    assertNull(failure.statusCode());
                } finally {
                    server.stop(0);
                }
            }
        }
    }

    private static void assertInvalidIdsRejected(OpenAICompatibleProvider provider) {
        for (String id : List.of("\"\"", "\"  \"", "42", "true")) {
            assertThrows(RuntimeException.class, () -> provider.parseResponse(
                    response(provider, "\"tool_calls\":[" + call(id) + "]")));
        }
        String duplicate = "\"tool_calls\":[" + call("\"same_id\"") + "," + call("\"same_id\"") + "]";
        assertThrows(RuntimeException.class, () -> provider.parseResponse(response(provider, duplicate)));
    }

    private static String call(String id) {
        return "{" + (id == null ? "" : "\"id\":" + id + ",")
                + "\"function\":{\"name\":\"inspect_inventory\",\"arguments\":{}}}";
    }

    private static void assertMalformedCallsRejected(OpenAICompatibleProvider provider) {
        for (String calls : List.of("{}", "42", "[42]", "[{}]",
                "[{\"function\":{}}]", "[{\"function\":{\"name\":\"\"}}]",
                "[{\"function\":{\"name\":42}}]",
                "[{\"function\":{\"name\":\"inspect_inventory\"}},42]")) {
            assertThrows(RuntimeException.class,
                    () -> provider.parseResponse(response(provider, "\"tool_calls\":" + calls)),
                    provider.providerId() + " must reject " + calls);
        }
    }

    private static String response(OpenAICompatibleProvider provider, String messageFields) {
        String message = "{\"role\":\"assistant\"," + messageFields + "}";
        return provider instanceof OllamaNativeProvider
                ? "{\"done\":true,\"message\":" + message + "}"
                : "{\"choices\":[{\"message\":" + message + "}]}";
    }
}
