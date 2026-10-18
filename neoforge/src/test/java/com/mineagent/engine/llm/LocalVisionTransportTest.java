package com.mineagent.engine.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.ChatMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalVisionTransportTest {
    private static final String PNG = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5WQAAAAASUVORK5CYII=";

    @Test
    void openAiCompatibleSendsImageBytesWithNaturalText() throws Exception {
        ChatMessage message = withImages("Review the spatial arrangement.", List.of(PNG));
        JsonObject body = request(new OpenAICompatibleProvider(), "/v1/chat/completions", message);
        var content = body.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content");
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(message.content(), content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("image_url", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals(PNG, content.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
        assertFalse(body.has("chat_template_kwargs"));
    }

    @Test
    void ollamaSendsNativeBase64ImagesWithoutLosingText() throws Exception {
        ChatMessage message = withImages("Observe the design.", List.of(PNG, PNG));
        JsonObject body = request(new OllamaNativeProvider(), "/api/chat", message);
        var encoded = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals(message.content(), encoded.get("content").getAsString());
        assertTrue(encoded.has("images"), "Native Ollama must receive the image bytes");
        assertEquals(2, encoded.getAsJsonArray("images").size());
        assertEquals(PNG.substring(PNG.indexOf(',') + 1), encoded.getAsJsonArray("images").get(0).getAsString());
        assertFalse(body.get("stream").getAsBoolean());
    }

    @Test
    void rejectsExternalUrlsAndInvalidInlineImageData() {
        for (String value : List.of("https://example.invalid/image.png", "file:///image.png",
                "data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,!!!!",
                "data:image/png;base64,SGVsbG8=")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ChatMessage.userWithImages("Observe.", List.of(value)), value);
        }
    }

    @Test
    void boundsEachObservationToSixImages() {
        assertDoesNotThrow(() -> ChatMessage.userWithImages("Observe.", java.util.Collections.nCopies(6, PNG)));
        assertThrows(IllegalArgumentException.class,
                () -> ChatMessage.userWithImages("Observe.", java.util.Collections.nCopies(7, PNG)));
    }

    @Test
    void rejectsOversizedInlineImagesBeforeSendingRequests() {
        byte[] bytes = new byte[2 * 1024 * 1024 + 1];
        byte[] signature = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        String image = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalArgumentException.class,
                () -> ChatMessage.userWithImages("Observe.", List.of(image)));
    }

    private static ChatMessage withImages(String text, List<String> images) {
        return assertDoesNotThrow(() -> (ChatMessage) ChatMessage.class
                .getMethod("userWithImages", String.class, List.class).invoke(null, text, images),
                "The message contract must carry image attachments for visual review");
    }

    private static JsonObject request(OpenAICompatibleProvider provider, String path,
                                      ChatMessage message) throws Exception {
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            captured.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            String response = path.equals("/api/chat")
                    ? "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"Reviewed.\"}}"
                    : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Reviewed.\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort()
                    + (path.startsWith("/v1/") ? "/v1" : "");
            provider.complete(base, "", "fixture-vision-model", List.of(message), List.of(), 0.1, 256, "auto");
            assertNotNull(captured.get(), "The real local HTTP fixture must receive a request");
            return captured.get();
        } finally {
            server.stop(0);
        }
    }
}
