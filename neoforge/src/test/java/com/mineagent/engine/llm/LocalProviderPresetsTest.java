package com.mineagent.engine.llm;

import com.mineagent.engine.client.ui.ProviderPresets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalProviderPresetsTest {
    @Test void exposesExactlyBonsaiAndOllama() {
        assertEquals(List.of("openai-compatible", "ollama"),
                ProviderPresets.ALL.stream().map(ProviderPresets.Preset::protocolId).toList());
        assertEquals("http://127.0.0.1:8899/v1", ProviderPresets.BONSAI.baseUrl());
        assertEquals("swift-bonsai2-27b-katana-mtp", ProviderPresets.BONSAI.preferredModel());
        assertEquals("http://127.0.0.1:11434", ProviderPresets.OLLAMA.baseUrl());
        assertTrue(ProviderPresets.OLLAMA.preferredModel().isEmpty(), "Ollama model is user-configured");
    }

    @Test void doesNotSilentlyTranslateRemovedCloudAdapters() {
        assertEquals("openai", ProviderPresets.protocolForProvider("openai"));
        assertTrue(ProviderPresets.find("openai", "https://api.openai.com").isEmpty());
        assertEquals(ProviderPresets.OLLAMA,
                ProviderPresets.find("ollama", "http://192.168.1.20:11434").orElseThrow());
    }
}
