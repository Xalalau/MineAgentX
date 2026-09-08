package com.mineagent.engine.client.ui;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Optional connection shortcuts for the unified setup screen.
 *
 * <p>Presets only fill protocol, model, and URL fields. They are deliberately
 * not a model allow-list: users can edit both model and endpoint afterward,
 * and the three protocol adapters remain the actual compatibility boundary.
 */
public final class ProviderPresets {

    public static final Preset CUSTOM = new Preset(
            "custom", Component.translatable("screen.mineagent.preset.custom"),
            "openai-compatible", "", "");

    public static final List<Preset> ALL = List.of(
            CUSTOM,
            preset("deepseek", "DeepSeek", "openai-compatible",
                    "deepseek-chat", "https://api.deepseek.com"),
            preset("openai", "OpenAI", "openai-compatible",
                    "gpt-4.1-mini", "https://api.openai.com"),
            preset("anthropic", "Anthropic", "anthropic-compatible",
                    "claude-sonnet-4-20250514", "https://api.anthropic.com"),
            preset("gemini", "Google Gemini", "gemini-compatible",
                    "gemini-2.5-flash", "https://generativelanguage.googleapis.com"),
            preset("aws-bedrock", "AWS Bedrock Converse", "aws-bedrock",
                    "anthropic.claude-3-5-sonnet-20241022-v2:0",
                    "https://bedrock-runtime.us-east-1.amazonaws.com"),
            preset("aws-bedrock-invoke", "AWS Bedrock InvokeModel", "aws-bedrock-invoke",
                    "anthropic.claude-3-5-sonnet-20241022-v2:0",
                    "https://bedrock-runtime.us-east-1.amazonaws.com"),
            preset("azure-openai", "Azure OpenAI", "azure-openai",
                    "gpt-4o", "https://YOUR_RESOURCE.openai.azure.com"),
            preset("vertex-ai", "Google Vertex AI", "vertex-ai",
                    "gemini-2.5-flash", "https://us-central1-aiplatform.googleapis.com"),
            preset("cohere", "Cohere", "cohere", "command-a-03-2025", "https://api.cohere.com"),
            preset("ai21", "AI21 Jamba", "ai21", "jamba-1.5-large", "https://api.ai21.com/studio/v1"),
            preset("baidu-qianfan", "Baidu Qianfan", "baidu-qianfan", "ernie-4.5-turbo-128k", "https://aip.baidubce.com"),
            preset("tencent-hunyuan", "Tencent Hunyuan", "tencent-hunyuan", "hunyuan-turbo", "https://hunyuan.tencentcloudapi.com"),
            preset("bytedance-ark", "ByteDance Ark", "bytedance-ark", "doubao-seed-1-6-250615", "https://ark.cn-beijing.volces.com/api/v3"),
            preset("ollama", "Ollama Native", "ollama", "llama3.2", "http://127.0.0.1:11434"),
            preset("qwen", "Alibaba Qwen", "openai-compatible",
                    "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode"),
            preset("glm", "Zhipu GLM", "openai-compatible",
                    "glm-4.5", "https://open.bigmodel.cn/api/paas"),
            preset("moonshot", "Moonshot / Kimi", "openai-compatible",
                    "kimi-k2.5", "https://api.moonshot.cn"),
            preset("grok", "xAI Grok", "openai-compatible",
                    "grok-4", "https://api.x.ai"),
            preset("minimax", "MiniMax", "openai-compatible",
                    "MiniMax-M2.5", "https://api.minimaxi.com"),
            preset("openrouter", "OpenRouter", "openai-compatible",
                    "openai/gpt-4.1-mini", "https://openrouter.ai/api"),
            preset("mistral", "Mistral AI", "openai-compatible",
                    "mistral-small-latest", "https://api.mistral.ai"),
            preset("groq", "Groq", "openai-compatible",
                    "llama-3.3-70b-versatile", "https://api.groq.com/openai"),
            preset("together", "Together AI", "openai-compatible",
                    "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                    "https://api.together.xyz")
    );

    private ProviderPresets() {}

    /** Translate a persisted pre-v0.3.5 vendor id to its wire protocol. */
    public static String protocolForProvider(String providerId) {
        if (providerId == null) return "openai-compatible";
        String normalized = providerId.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "anthropic", "anthropic-compatible" -> "anthropic-compatible";
            case "gemini", "gemini-compatible" -> "gemini-compatible";
            case "aws-bedrock", "bedrock" -> "aws-bedrock";
            case "aws-bedrock-invoke", "bedrock-invoke" -> "aws-bedrock-invoke";
            case "azure", "azure-openai" -> "azure-openai";
            case "vertex", "vertex-ai" -> "vertex-ai";
            case "cohere" -> "cohere";
            case "ai21", "jamba" -> "ai21";
            case "baidu", "baidu-qianfan", "qianfan" -> "baidu-qianfan";
            case "tencent", "tencent-hunyuan", "hunyuan" -> "tencent-hunyuan";
            case "ark", "bytedance", "bytedance-ark" -> "bytedance-ark";
            case "ollama" -> "ollama";
            case "openai", "deepseek", "qwen", "glm", "moonshot", "grok",
                    "minimax", "openai-compatible" -> "openai-compatible";
            // Preserve third-party adapter ids registered through
            // LLMProviderRegistry instead of silently changing their protocol.
            default -> normalized.isEmpty() ? "openai-compatible" : normalized;
        };
    }

    /** Find the shortcut represented by persisted legacy or generic settings. */
    public static Optional<Preset> find(String providerId, String baseUrl) {
        String provider = providerId == null ? "" : providerId.trim();
        String base = normalizeBase(baseUrl);
        for (Preset preset : ALL) {
            if (preset == CUSTOM) continue;
            if (preset.legacyId().equalsIgnoreCase(provider)) {
                return Optional.of(preset);
            }
            if (!base.isEmpty() && normalizeBase(preset.baseUrl()).equalsIgnoreCase(base)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    private static Preset preset(String legacyId, String label, String protocolId,
                                 String preferredModel, String baseUrl) {
        return new Preset(legacyId, Component.literal(label), protocolId,
                preferredModel, baseUrl);
    }

    private static String normalizeBase(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record Preset(String legacyId, Component label, String protocolId,
                         String preferredModel, String baseUrl) {}
}
