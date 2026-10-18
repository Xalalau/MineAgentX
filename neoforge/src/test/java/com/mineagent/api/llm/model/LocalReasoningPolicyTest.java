package com.mineagent.api.llm.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class LocalReasoningPolicyTest {
    @Test
    void blankAndAutoUseServerDefaultsWhileExplicitBudgetsAreRejected() {
        for (String value : new String[]{null, "", " ", "auto", " AUTO "}) {
            assertEquals("auto", ThinkingEffortSpec.normalize(value));
        }
        for (String value : new String[]{"off", "low", "medium", "high", "xhigh", "max", "automatic", "auto extra"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ThinkingEffortSpec.normalize(value));
        }
        var spec = ThinkingEffortSpec.forModel("local-model");
        assertEquals(List.of("auto"), spec.supportedLevels());
        assertFalse(spec.canDisable());
        assertFalse(spec.supportsEffort());
    }

    @Test
    void restorationMigratesOnlyKnownLegacyLocalEffortsWithADiagnostic() {
        for (String provider : new String[]{"openai-compatible", "ollama"}) {
            for (String effort : new String[]{"off", "low", "medium", "high", "xhigh", "max", " HIGH "}) {
                var result = ThinkingEffortSpec.restore(provider, effort);
                assertEquals("auto", result.effort());
                String diagnostic = result.diagnostic();
                assertTrue(diagnostic.contains(effort.trim().toLowerCase(Locale.ROOT)));
                assertTrue(diagnostic.contains("migrated to auto"));
                assertTrue(diagnostic.contains("server-managed"));
            }
        }
    }

    @Test
    void restorationRejectsUnknownAndCloudProviderIdsWithoutRemapping() {
        for (String provider : new String[]{null, "", "openai", "anthropic", "bedrock", "ollama-native", "OLLAMA", " ollama"}) {
            for (String effort : new String[]{null, "auto", "off", "high"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> ThinkingEffortSpec.restore(provider, effort), provider + ": " + effort);
            }
        }
    }

    @Test
    void restorationKeepsAutomaticSavesQuietAndRejectsInvalidNonlegacyEfforts() {
        for (String provider : new String[]{"openai-compatible", "ollama"}) {
            for (String effort : new String[]{null, "", " ", "auto", " AUTO "}) {
                var result = ThinkingEffortSpec.restore(provider, effort);
                assertEquals("auto", result.effort());
                assertEquals("", result.diagnostic());
            }
            for (String effort : new String[]{"automatic", "none", "true", "0", "ultra", "auto extra", "high extra"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> ThinkingEffortSpec.restore(provider, effort));
            }
        }
    }
}
