package com.mineagent.api.i18n;

import com.mineagent.api.config.MineAgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTest {
    @TempDir Path directory;

    @Test
    void explicitPortuguesePoliciesRoundTripWithoutChangingConnections() throws Exception {
        String json = """
                {"language":"pt_br","safety":{"allowPlayerAndCompanionAttacks":true},
                 "vision":{"enabled":true,"provider":"ollama","model":"actual-vision-model",
                           "apiKey":"local-test-token","baseUrl":"http://127.0.0.1:11434"},
                 "reflection":{"intervalTicks":2400},
                 "llm":{"provider":"ollama","model":"actual-text-model"}}
                """;
        Files.writeString(directory.resolve("mineagent.json"), json);
        MineAgentConfig config = MineAgentConfig.load(directory);
        assertEquals("pt_br", config.language());
        assertTrue(config.safety().allowPlayerAndCompanionAttacks());
        assertTrue(config.vision().configured());
        assertEquals("local-test-token", config.vision().apiKey());
        assertEquals("actual-text-model", config.llm().model());
        assertEquals(2400, config.reflection().intervalTicks());
        assertTrue(config.save(directory));
        assertEquals(config, MineAgentConfig.load(directory));
    }

    @Test
    void reflectionIntervalsAreBoundedAndMayBeDisabled() throws Exception {
        int[][] values = {{-1,1200},{0,0},{1,200},{200,200},{72000,72000},{999999,72000}};
        for (int[] pair : values) {
            Files.writeString(directory.resolve("mineagent.json"),
                    "{\"reflection\":{\"intervalTicks\":" + pair[0] + "}}");
            assertEquals(pair[1], MineAgentConfig.load(directory).reflection().intervalTicks());
        }
    }

    @Test
    void directConstructionValidatesLanguageAndSuppliesMissingPolicySections() {
        MineAgentConfig defaults = MineAgentConfig.DEFAULTS;
        MineAgentConfig direct = new MineAgentConfig(defaults.llm(), defaults.companion(),
                defaults.survival(), defaults.pathfinding(), null, null, null, null);
        assertEquals(defaults, direct);
        assertThrows(IllegalArgumentException.class, () -> new MineAgentConfig(defaults.llm(),
                defaults.companion(), defaults.survival(), defaults.pathfinding(),
                "pt-br", defaults.safety(), defaults.vision(), defaults.reflection()));
    }

    @Test
    void visionRequiresExplicitEnablementProtocolModelAndEndpoint() throws Exception {
        for (String json : new String[]{"{}", "{\"enabled\":true}",
                "{\"enabled\":true,\"provider\":\"ollama\"}",
                "{\"enabled\":true,\"provider\":\"unsupported\",\"model\":\"chosen\",\"baseUrl\":\"http://127.0.0.1:9000\"}",
                "{\"provider\":\"ollama\",\"model\":\"chosen\",\"baseUrl\":\"http://127.0.0.1:11434\"}"}) {
            Files.writeString(directory.resolve("mineagent.json"), "{\"vision\":" + json + "}");
            assertFalse(MineAgentConfig.load(directory).vision().configured());
        }
        MineAgentConfig.VisionConfig normalized = new MineAgentConfig.VisionConfig(true,
                "ollama", null, null, null);
        assertEquals("", normalized.apiKey());
        assertEquals("", normalized.model());
        assertEquals("", normalized.baseUrl());
    }

    @Test
    void unsupportedLanguageRejectsReloadWithoutOverwritingTheUserFile() throws Exception {
        String json = "{\"language\":\"pt-br\",\"llm\":{\"provider\":\"ollama\",\"model\":\"chosen\"}}";
        Files.writeString(directory.resolve("mineagent.json"), json);
        assertThrows(IllegalArgumentException.class, () -> MineAgentConfig.load(directory));
        assertEquals(json, Files.readString(directory.resolve("mineagent.json")));
    }

    @Test
    void malformedLocaleValuesNeverTriggerDestructiveEnglishFallback() throws Exception {
        for (String locale : new String[]{"{}", "[]", "true", "7", "\"\"", "\"PT_BR\""}) {
            String json = "{\"language\":" + locale + "}";
            Files.writeString(directory.resolve("mineagent.json"), json);
            assertThrows(IllegalArgumentException.class, () -> MineAgentConfig.load(directory));
            assertEquals(json, Files.readString(directory.resolve("mineagent.json")));
        }
    }

    @Test
    void oldFilesAndFourArgumentConstructorsReceiveSafeEnglishDefaults() throws Exception {
        Files.writeString(directory.resolve("mineagent.json"), "{\"llm\":{\"provider\":\"ollama\"}}");
        MineAgentConfig config = MineAgentConfig.load(directory);
        assertEquals("en_us", config.language());
        assertFalse(config.safety().allowPlayerAndCompanionAttacks());
        assertFalse(config.vision().enabled());
        assertFalse(config.vision().configured());
        assertEquals("", config.vision().model());
        assertEquals(1200, config.reflection().intervalTicks());
        MineAgentConfig legacy = new MineAgentConfig(config.llm(), config.companion(),
                config.survival(), config.pathfinding());
        assertEquals(config, legacy);
        assertEquals("", config.llm().model());
    }
}
