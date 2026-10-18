package com.mineagent.engine.client.ui;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Local connection shortcuts. Model aliases and private LAN endpoints remain editable. */
public final class ProviderPresets {
    /** Sentinel used when persisted settings do not match a supported local preset. */
    public static final Preset CUSTOM = new Preset("custom", Component.literal("Custom local server"),
            "openai-compatible", "", "");
    public static final Preset BONSAI = new Preset("openai-compatible", Component.literal("Bonsai / llama.cpp (MTP)"),
            "openai-compatible", "swift-bonsai2-27b-katana-mtp", "http://127.0.0.1:8899/v1");
    public static final Preset OLLAMA = new Preset("ollama", Component.literal("Ollama (local)"),
            "ollama", "", "http://127.0.0.1:11434");
    public static final List<Preset> ALL = List.of(BONSAI, OLLAMA);

    private ProviderPresets() {}

    /** Preserve unsupported persisted IDs so the server rejects them explicitly. */
    public static String protocolForProvider(String providerId) {
        return providerId == null || providerId.isBlank()
                ? "openai-compatible" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    public static Optional<Preset> find(String providerId, String baseUrl) {
        String protocol = protocolForProvider(providerId);
        return ALL.stream().filter(preset -> preset.protocolId().equals(protocol)).findFirst();
    }

    public record Preset(String legacyId, Component label, String protocolId,
                         String preferredModel, String baseUrl) {}
}
