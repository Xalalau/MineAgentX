package com.mineagent.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MineAgentConfigTest {
    @TempDir
    Path configDir;

    @Test
    void partialOllamaConfigKeepsChosenModelAndUsesNativeEndpoint() throws IOException {
        MineAgentConfig config = load("""
                {"llm":{"provider":"ollama","model":"chosen-local-model"}}
                """);

        assertEquals("http://127.0.0.1:11434", config.llm().baseUrl());
        assertEquals("chosen-local-model", config.llm().model());
        assertEquals("ollama", config.llm().provider());
    }

    @Test
    void providerOnlyOllamaConfigRequiresAnExplicitModel() throws IOException {
        MineAgentConfig config = load("""
                {"llm":{"provider":"ollama"}}
                """);

        assertEquals("", config.llm().model());
        assertEquals("http://127.0.0.1:11434", config.llm().baseUrl());
    }

    @Test
    void supportedProviderIdsUseLocaleIndependentCaseNormalization() throws IOException {
        var originalLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            MineAgentConfig ollama = load("""
                    {"llm":{"provider":"  OlLaMa  "}}
                    """);
            assertEquals("ollama", ollama.llm().provider());
            assertEquals("http://127.0.0.1:11434", ollama.llm().baseUrl());
            assertEquals("", ollama.llm().model());

            MineAgentConfig bonsai = load("""
                    {"llm":{"provider":"  OPENAI-COMPATIBLE  "}}
                    """);
            assertEquals("openai-compatible", bonsai.llm().provider());
            assertEquals(MineAgentConfig.LLMConfig.DEFAULTS, bonsai.llm());
        } finally {
            java.util.Locale.setDefault(originalLocale);
        }
    }

    @Test
    void explicitOllamaEndpointAndGenerationOverridesArePreserved() throws IOException {
        String json = """
                {"llm":{"provider":"ollama","model":"chosen-local-model",
                  "baseUrl":"http://192.168.1.20:11435","apiKey":"local-test-token",
                  "temperature":0.25,"maxTokens":8192}}
                """;
        MineAgentConfig config = load(json);

        assertEquals(new MineAgentConfig.LLMConfig("ollama", "local-test-token",
                "chosen-local-model", "http://192.168.1.20:11435", 0.25, 8192), config.llm());
        assertEquals(json, Files.readString(configDir.resolve("mineagent.json")));
    }

    @Test
    void explicitlyEmptyOllamaEndpointIsNotReplaced() throws IOException {
        MineAgentConfig config = load("""
                {"llm":{"provider":"ollama","baseUrl":""}}
                """);

        assertEquals("", config.llm().baseUrl());
        assertEquals("", config.llm().model());
    }

    @Test
    void nullOrBlankOllamaModelsNeverInheritTheBonsaiAlias() throws IOException {
        for (String model : new String[]{"null", "\"\"", "\"   \""}) {
            MineAgentConfig config = load("{\"llm\":{\"provider\":\"ollama\",\"model\":"
                    + model + ",\"baseUrl\":null}}");
            assertEquals("", config.llm().model());
            assertEquals("http://127.0.0.1:11434", config.llm().baseUrl());
        }
    }

    @Test
    void missingSectionsAndProviderOnlyBonsaiKeepExistingDefaults() throws IOException {
        for (String json : new String[]{"{}", "{\"llm\":{}}", "{\"llm\":null}",
                "{\"llm\":{\"provider\":\"openai-compatible\"}}",
                "{\"llm\":{\"provider\":null}}", "{\"llm\":{\"provider\":\"   \"}}"}) {
            assertEquals(MineAgentConfig.DEFAULTS, load(json));
        }
        assertEquals("http://127.0.0.1:8899/v1", MineAgentConfig.LLMConfig.DEFAULTS.baseUrl());
        assertEquals("swift-bonsai2-27b-katana-mtp", MineAgentConfig.LLMConfig.DEFAULTS.model());
    }

    @Test
    void explicitBonsaiConnectionOverridesArePreserved() throws IOException {
        MineAgentConfig config = load("""
                {"llm":{"provider":"openai-compatible","model":"custom-local-alias",
                  "baseUrl":"http://192.168.1.30:9000/v1"}}
                """);

        assertEquals("custom-local-alias", config.llm().model());
        assertEquals("http://192.168.1.30:9000/v1", config.llm().baseUrl());
    }

    @Test
    void unsupportedProviderIdsAreNotRemappedToLocalAdapters() throws IOException {
        for (String provider : new String[]{"openai", "anthropic", "deepseek", "bonsai", "ollama-native"}) {
            MineAgentConfig config = load("{\"llm\":{\"provider\":\"" + provider
                    + "\",\"model\":\"legacy-model\",\"baseUrl\":\"http://127.0.0.1:9000\"}}");
            assertEquals(provider, config.llm().provider());
            assertEquals("legacy-model", config.llm().model());
            assertEquals("http://127.0.0.1:9000", config.llm().baseUrl());
        }
    }

    @Test
    void partialOllamaConfigStillMergesNestedDefaultsAndExplicitSafetyFlags() throws IOException {
        MineAgentConfig config = load("""
                {"llm":{"provider":"ollama"},
                 "companion":{"name":"Local Companion","gameMode":"CREATIVE","instantBreak":true},
                 "survival":{"autoEat":false,"fightBack":null},
                 "pathfinding":{"allowBridge":false},"unknownSection":{"enabled":true}}
                """);

        assertEquals(new MineAgentConfig.CompanionConfig("Local Companion", "creative", "", true, false),
                config.companion());
        assertEquals(new MineAgentConfig.SurvivalConfig(6, 12, 6.0, 60, false, true, true, true),
                config.survival());
        assertEquals(new MineAgentConfig.PathfindingConfig(5000, true, false, true), config.pathfinding());
        assertEquals("", config.llm().model());
    }

    @Test
    void partialOllamaConfigStillNormalizesInvalidNumericSettings() throws IOException {
        MineAgentConfig config = load("""
                {"llm":{"provider":"ollama","temperature":5,"maxTokens":-1},
                 "survival":{"foodCritical":25,"foodLow":-3,"healthFlee":-1,"stuckTimeTicks":0},
                 "pathfinding":{"maxSearchNodes":1}}
                """);

        assertEquals(0.7, config.llm().temperature());
        assertEquals(4096, config.llm().maxTokens());
        assertEquals(new MineAgentConfig.SurvivalConfig(20, 20, 6.0, 1, true, true, true, true),
                config.survival());
        assertEquals(new MineAgentConfig.PathfindingConfig(128, true, true, true), config.pathfinding());
    }

    private MineAgentConfig load(String json) throws IOException {
        Files.writeString(configDir.resolve("mineagent.json"), json);
        return MineAgentConfig.load(configDir);
    }
}
