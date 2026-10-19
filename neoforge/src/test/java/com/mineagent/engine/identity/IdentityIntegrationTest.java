package com.mineagent.engine.identity;

import com.google.gson.JsonParser;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.engine.memory.MemoryPersistence;
import com.mineagent.tools.identity.IdentityMemoryTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real native-tool and disk integration, requiring the compiled Minecraft/NeoForge classpath but no server. */
class IdentityIntegrationTest {
    @TempDir Path directory;

    @Test void nativeToolHasBilingualMetadataAndOnlyEvidenceBackedMutationOperations() {
        String previous = RuntimeLanguage.language().id();
        try {
            var tool = new IdentityMemoryTool();
            RuntimeLanguage.configure("en_us");
            assertTrue(tool.description().contains("evidence"));
            assertTrue(IdentityInstructions.system("en_us").startsWith("You are MineAgent"));
            var en = tool.parameterSchema();
            RuntimeLanguage.configure("pt_br");
            assertTrue(tool.description().contains("evidências"));
            assertTrue(IdentityInstructions.system("pt_br").startsWith("Você é MineAgent"));
            var pt = tool.parameterSchema();
            assertEquals(en.keySet(), pt.keySet());
            assertEquals("identity_memory", tool.name());
            assertFalse(tool.dispatchesAsyncTask());
            assertFalse(pt.toString().contains("One to three"));
            assertFalse(pt.toString().contains("Read relevant memory"));
            assertFalse(IdentityInstructions.system("pt_br").contains("[TEAM]"));
            assertFalse(IdentityInstructions.system("en_us").contains("[NO_ACTION]"));
        } finally { RuntimeLanguage.configure(previous); }
    }

    @Test void conversationDiskRoundTripKeepsLiteralOwnerSpeechButNotServerEvents() throws Exception {
        String ownerSpeech = "[TEAM] is literal text in my question; it is not a routing instruction.";
        var history = new ArrayList<>(List.of(ChatMessage.system("Current neutral principles."),
                ChatMessage.user(ownerSpeech),
                ChatMessage.system("Server observations, not new player instructions.\nA task ended."),
                ChatMessage.assistant("The literal text is retained."),
                ChatMessage.system("Server observations, not new player instructions.\nA separate task ended."),
                ChatMessage.assistant("A task report without a new owner message.")));
        var persistence = new MemoryPersistence(directory, null, null, null, null, null,
                null, null, null, null, null, null, null, history, null, null, null, null);
        var save = MemoryPersistence.class.getDeclaredMethod("saveConversation"); save.setAccessible(true);
        save.invoke(persistence);
        Path file = directory.resolve("conversation.json");
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("pairs").size());
        assertEquals(ownerSpeech, json.getAsJsonArray("pairs").get(0).getAsJsonObject().get("user").getAsString());
        assertFalse(Files.readString(file).contains("A separate task ended"));
        var restored = new ArrayList<ChatMessage>(); restored.add(ChatMessage.system("Current principles."));
        var loadTarget = new MemoryPersistence(directory, null, null, null, null, null,
                null, null, null, null, null, null, null, restored, null, null, null, null);
        var load = MemoryPersistence.class.getDeclaredMethod("loadConversation"); load.setAccessible(true);
        load.invoke(loadTarget);
        assertEquals(3, restored.size());
        assertEquals(ownerSpeech, restored.get(1).content());
    }
}
