package com.mineagent.neoforge.regression;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring guards supplement executable policy tests without bootstrapping Minecraft. */
class LocalReasoningWiringTest {
    private static Path projectRoot() {
        String configured = System.getProperty("mineagentx.projectRoot");
        Path candidate = Path.of(configured == null ? "" : configured).toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("AGENTS.md"))) {
            candidate = candidate.getParent();
        }
        return java.util.Objects.requireNonNull(candidate, "Project root not found");
    }

    private static String source(String path) throws IOException {
        return Files.readString(projectRoot().resolve(path));
    }

    @Test
    void bothClientEffortInputsUseTheTestedPolicyBeforeDispatch() throws IOException {
        String runtime = source("engine/src/client/java/com/mineagent/engine/client/screen/ConfigEditScreen.java");
        String spawn = source("engine/src/client/java/com/mineagent/engine/client/screen/SpawnCompanionScreen.java");
        assertTrue(runtime.contains("ThinkingEffortSpec.normalize("), "Runtime screen must share the server policy");
        assertTrue(spawn.contains("ThinkingEffortSpec.normalize("), "Creation screen must share the server policy");
        assertFalse(runtime.contains("List.of(\"off\""));
        assertTrue(runtime.indexOf("ThinkingEffortSpec.normalize(") < runtime.indexOf("connection.sendCommand("));
        assertTrue(spawn.indexOf("ThinkingEffortSpec.normalize(") < spawn.indexOf("sendCompanionSetup("));
        assertTrue(runtime.contains("rawEffort.isEmpty()"), "Blank runtime effort leaves the current setting unchanged");
    }

    @Test
    void restorationUsesTheTestedMigrationWithoutRewritingConnectionFields() throws IOException {
        String engine = source("engine/src/main/java/com/mineagent/engine/MineAgentEngine.java");
        int migration = engine.indexOf("ThinkingEffortSpec.restore(providerId, reasoningEffort)");
        int validation = engine.indexOf("String invalid = validateSpawnArguments(");
        assertTrue(migration >= 0, "Actual restore path must invoke the migration");
        assertTrue(migration < validation, "Migration must precede strict spawn validation");
        assertTrue(engine.contains("restored.diagnostic()"), "Legacy migration needs a visible diagnostic");
        assertTrue(engine.contains("ThinkingEffortSpec.normalize(reasoningEffort)"));
        assertTrue(engine.contains("ThinkingEffortSpec.normalize(requestedEffort)"));
        assertTrue(engine.contains("saved.providerId(),"));
        assertTrue(engine.contains("saved.baseUrl()"));
        assertTrue(engine.contains("provider.defaultBaseUrl() : baseUrl"), "Explicit saved endpoints remain validated, not replaced");
    }

    @Test
    void runtimeDispatchReportsQueuedRatherThanApplied() throws IOException {
        String runtime = source("engine/src/client/java/com/mineagent/engine/client/screen/ConfigEditScreen.java");
        var locale = com.google.gson.JsonParser.parseString(source(
                "engine/src/client/resources/assets/mineagent/lang/en_us.json")).getAsJsonObject();
        assertTrue(runtime.contains("screen.mineagent.runtime.queued"));
        assertFalse(runtime.contains("screen.mineagent.runtime.applied"));
        assertFalse(locale.has("screen.mineagent.runtime.applied"));
        String message = locale.get("screen.mineagent.runtime.queued").getAsString().toLowerCase(java.util.Locale.ROOT);
        assertTrue(message.contains("queued"));
        assertTrue(message.contains("server"));
        assertFalse(message.contains("settings applied"));
    }

    @Test
    void helpAndLocaleDescribeOnlyAutomaticLocalReasoning() throws IOException {
        var locale = com.google.gson.JsonParser.parseString(source(
                "engine/src/client/resources/assets/mineagent/lang/en_us.json")).getAsJsonObject();
        String error = locale.get("screen.mineagent.error.effort").getAsString();
        assertTrue(error.contains("auto"));
        assertTrue(error.contains("blank"));
        assertFalse(error.contains("xhigh"));
        assertTrue(locale.get("screen.mineagent.effort_hint").getAsString().contains("server"));
        assertTrue(locale.get("screen.mineagent.effort_runtime_hint").getAsString().contains("blank keeps current"));
        assertFalse(locale.has("screen.mineagent.bedrock_credentials_hint"));
        String engine = source("engine/src/main/java/com/mineagent/engine/MineAgentEngine.java");
        assertFalse(engine.contains("<off|low|medium|high|xhigh|max>"));
        assertTrue(engine.contains("[name] <auto>"));
    }
}
