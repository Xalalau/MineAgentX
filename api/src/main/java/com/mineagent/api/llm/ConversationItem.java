package com.mineagent.api.llm;

import java.util.Objects;

/**
 * Provider-neutral conversation item.
 *
 * <p>The raw JSON field is intentionally opaque.  Responses can return
 * encrypted reasoning and compaction items which must be replayed byte-for-
 * byte but must never be interpreted as user-visible text or MineAgent
 * semantic memory.</p>
 */
public record ConversationItem(String type, String role, String text,
                                String callId, String rawJson) {
    public ConversationItem {
        type = type == null || type.isBlank() ? "message" : type;
        role = role == null || role.isBlank() ? null : role;
        text = text == null || text.isBlank() ? null : text;
        callId = callId == null || callId.isBlank() ? null : callId;
        rawJson = rawJson == null || rawJson.isBlank() ? null : rawJson;
    }

    public boolean opaque() {
        return rawJson != null && (type.contains("reasoning")
                || type.contains("compaction") || type.contains("output"));
    }

    public static ConversationItem message(String role, String text) {
        return new ConversationItem("message", Objects.requireNonNull(role),
                text, null, null);
    }

    public static ConversationItem opaque(String type, String rawJson) {
        return new ConversationItem(type, null, null, null,
                Objects.requireNonNull(rawJson));
    }
}
