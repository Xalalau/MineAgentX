package com.mineagent.api.llm;

import java.util.List;
import java.util.Map;

/**
 * A chat message in the LLM conversation.
 */
public record ChatMessage(String role, String content, List<ToolCallRef> toolCalls, String toolCallId,
                          List<String> images) {

    /** Preserve the text-only conversation and saved-history contract. */
    public ChatMessage(String role, String content, List<ToolCallRef> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
    }

    public ChatMessage {
        images = images == null ? List.of() : List.copyOf(images);
        if (images.size() > 6) throw new IllegalArgumentException("At most six images are allowed per observation");
        for (String image : images) validateImage(image);
    }

    private static void validateImage(String image) {
        boolean png = image.startsWith("data:image/png;base64,");
        boolean jpeg = image.startsWith("data:image/jpeg;base64,");
        if (!png && !jpeg) throw new IllegalArgumentException("Images require inline PNG or JPEG data");
        int offset = image.indexOf(',') + 1;
        int byteLimit = 2 * 1024 * 1024;
        if (image.length() - offset > 4 * ((byteLimit + 2) / 3)) {
            throw new IllegalArgumentException("Image exceeds the observation byte limit");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(image.substring(offset));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid image encoding", invalid);
        }
        if (bytes.length > byteLimit) throw new IllegalArgumentException("Image exceeds the observation byte limit");
        byte[] signature = png ? new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}
                : new byte[]{(byte) 255, (byte) 216, (byte) 255};
        if (bytes.length < signature.length) throw new IllegalArgumentException("Invalid image signature");
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) throw new IllegalArgumentException("Invalid image signature");
        }
    }

    /** Create a visual observation with inline image data, not remote URLs. */
    public static ChatMessage userWithImages(String content, List<String> images) {
        return new ChatMessage("user", content, null, null, images);
    }

    /** Create a system message. */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    /** Create a user message. */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    /** Create an assistant message. */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    /** Create an assistant message with tool calls. */
    public static ChatMessage assistantWithTools(List<ToolCallRef> toolCalls) {
        return new ChatMessage("assistant", null, toolCalls, null);
    }

    /** Create an assistant message with both content and tool calls. */
    public static ChatMessage assistant(String content, List<ToolCallRef> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    /** Create a tool result message. */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    /** A reference to a tool call in an assistant message. */
    public record ToolCallRef(String id, String name, String arguments) {}
}
