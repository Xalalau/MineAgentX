package com.mineagent.api.llm;

import com.mineagent.api.llm.provider.LLMProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LLMContractTest {
    @Test
    void requestNormalizesUnsafeAndMissingValues() {
        LLMRequest request = new LLMRequest("https://example.test", "key", "model",
                List.of(ChatMessage.user("hello")), List.of(ChatMessage.user("hello")),
                List.of(), List.of(),
                Double.NaN, 0, " ", " ", " ", " ", Duration.ZERO, false);
        assertEquals(0.0, request.temperature());
        assertEquals(1, request.maxTokens());
        assertEquals(Duration.ofSeconds(30), request.deadline());
        assertNull(request.reasoningEffort());
    }

    @Test
    void opaqueItemsAreNeverInterpretedAsText() {
        ConversationItem item = ConversationItem.opaque("compaction", "{\"type\":\"compaction\"}");
        assertTrue(item.opaque());
        assertNull(item.text());
        assertEquals("{\"type\":\"compaction\"}", item.rawJson());
    }

    @Test
    void legacyProviderReceivesStateAwareBridge() {
        LLMProvider provider = new LLMProvider() {
            @Override public String providerId() { return "test"; }
            @Override public String displayName() { return "test"; }
            @Override public String defaultBaseUrl() { return "https://example.test"; }
            @Override public boolean supportsModel(String model) { return true; }
            @Override public List<String> defaultModels() { return List.of(); }
            @Override public LLMResponse complete(String baseUrl, String apiKey, String model,
                                                  List<ChatMessage> messages,
                                                  List<Map<String, Object>> tools,
                                                  double temperature, int maxTokens,
                                                  String reasoningEffort) {
                return new LLMResponse("id", model,
                        new LLMResponse.Choice(0, ChatMessage.assistant("ok"), "stop"),
                        new LLMResponse.Usage(1, 1, 2), "stop");
            }
        };
        LLMResponse response = provider.complete(new LLMRequest(
                "https://example.test", "key", "model",
                List.of(ChatMessage.user("hello")), List.of(ChatMessage.user("hello")),
                List.of(), List.of(),
                0.0, 10, null, null, null, null,
                Duration.ofSeconds(1), false));
        assertEquals("ok", response.choice().message().content());
    }
}
