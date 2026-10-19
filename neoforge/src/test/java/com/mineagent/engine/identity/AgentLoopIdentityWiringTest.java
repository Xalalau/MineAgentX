package com.mineagent.engine.identity;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Supplementary wiring guards; policy, revisions and disk round trips run against real classes. */
class AgentLoopIdentityWiringTest {
    @Test void everyDecisionRoundReceivesCurrentCommitmentsAfterSynchronousMemoryUpdates() throws Exception {
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        assertFalse(loop.contains("if (roundNumber == 0) {\n            // Keep volatile state"));
        assertTrue(loop.contains("ChatMessage live = ChatMessage.user(buildLiveContext())"));
    }

    @Test void languageReloadCannotKeepThePreviousLanguagesSystemPrompt() throws Exception {
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        assertTrue(loop.contains("refreshSystemPromptIfNeeded();"));
        assertTrue(loop.contains("history.set(0, ChatMessage.system(prompt))"));
        assertTrue(loop.contains("mineagent-prompt-v5:"));
    }

    @Test void serverObservationsCannotBecomePersistedPlayerSpeech() throws Exception {
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        assertTrue(loop.contains("history.add(ChatMessage.system(sb.toString()))"));
        assertFalse(loop.contains("history.add(ChatMessage.user(sb.toString()))"));
    }

    @Test void recalledMemoryAndPredictedPlansAreNotRelabeledAsFreshObservation() throws Exception {
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        // Callback arbitration now carries the immutable execution descriptor, not the old tool-call local.
        assertTrue(loop.contains("IDENTITY_OBSERVATION_TOOLS.contains(execution.name())"));
        assertFalse(loop.substring(loop.indexOf("private static final Set<String> IDENTITY_OBSERVATION_TOOLS"),
                loop.indexOf(";", loop.indexOf("private static final Set<String> IDENTITY_OBSERVATION_TOOLS")))
                .contains("recall_memory"));
        assertFalse(loop.contains("SKILL_TRACE_TOOLS.contains(tc.name()) || SKILL_ACTION_TOOLS.contains(tc.name())"));
    }

    @Test void decisionContextAndSummaryFramingAreLocalizedProseNotBracketProtocols() throws Exception {
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        assertFalse(loop.contains("return \"Location memory: (None)"));
        assertFalse(loop.contains("\"{\\\"error\\\":\\\"Tool execution timed out"));
        assertFalse(loop.contains("\"Skipped because this response already dispatched "));
        assertFalse(loop.contains("sb.append(\"The following facts"));
        assertFalse(loop.contains("sb.append(\"Mode: "));
        assertFalse(loop.contains("out.append(\"Body executor:"));
        assertFalse(loop.contains("ChatMessage.system(\"[SUMMARY]"));
        assertFalse(loop.contains("\"[SYSTEM] You have called tools"));
        assertFalse(loop.contains("sb.insert(lastSemi, \"[IMPORTANT]"));
    }

    @Test void nativeToolDelegatesToTheLoopsEvidenceBackedAccessBoundary() throws Exception {
        String tool = source("tools/src/main/java/com/mineagent/tools/identity/IdentityMemoryTool.java");
        assertTrue(tool.contains("loop.identityMemory(args)"));
        assertTrue(tool.contains("RuntimeLanguage.language()"));
        assertFalse(tool.contains("recordEvidence("));
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        assertTrue(loop.contains("identityMemoryAccess.execute(args"));
    }

    private static String source(String relative) throws Exception {
        Path root = Files.isDirectory(Path.of("engine")) ? Path.of(".") : Path.of("..");
        return Files.readString(root.resolve(relative));
    }

    @Test void liveDecisionRoundUsesLearnedIdentityInsteadOfAssignedPersonality() throws Exception {
        String loop = source("engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java");
        assertTrue(loop.contains("identity.decisionContext(memoryQuery"));
        assertTrue(loop.contains("identityReflection.open("));
        assertTrue(loop.contains("identity.recordEvidence(\"player_claim\""));
        assertTrue(loop.contains("identity.recordEvidence(\"observation\""));
        assertTrue(loop.contains("identityReflection.close()"));
        assertFalse(loop.contains("PersonaProfile.random()"));
        assertFalse(loop.contains("compactPersona()"));
        assertFalse(loop.contains("persona.drift("));
        assertFalse(loop.contains("**Examples**"));
        assertFalse(loop.contains("Action Intent Declaration Protocol (MANDATORY)"));
        assertFalse(loop.contains("if (owner != null && isExplicitTeamMessage(text))"),
                "Conversation must not depend on a team chat tag");
        String memory = source("engine/src/main/java/com/mineagent/engine/memory/MemoryPersistence.java");
        assertTrue(memory.contains("IdentityPersistence.save(memoryDir"));
        assertTrue(memory.contains("IdentityPersistence.load(memoryDir"));
        assertTrue(memory.contains("IdentityPersistence.FILE_NAME"));
    }
}
