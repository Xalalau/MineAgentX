package com.mineagent.engine.conversation;

import com.mineagent.api.i18n.RuntimeLanguage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ConversationDeliveryTest {
    @Test
    void usesSelectedLanguageWithoutTranslatingOrRewritingPlayerSpeech() {
        RuntimeLanguage.configure("pt_br");
        try {
            AtomicReference<String> heard = new AtomicReference<>();
            String speech = "Preserve this original text: {0} @name";
            ConversationDelivery.deliverSpeech("Player", speech, heard::set);
            assertEquals("Player disse: " + speech, heard.get());
        } finally {
            RuntimeLanguage.configure("en_us");
        }
    }

    @Test
    void companionSpeechUsesNaturalAttributionOnTheSeparateTeamChannel() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("engine"))) root = root.getParent();
        String source = Files.readString(root.resolve("engine/src/main/java/com/mineagent/engine/MineAgentEngine.java"));
        int start = source.indexOf("public static void broadcastToOtherCompanions(");
        String routing = source.substring(start, source.indexOf("    // ── Player join/leave", start));
        assertTrue(routing.contains("ConversationDelivery.deliverSpeech"));
        assertTrue(routing.contains("s.loop::onTeamEvent"));
        assertFalse(routing.contains("onOwnerMessage("));
        assertFalse(routing.contains("\"from=\""));
    }

    @Test
    void forwardsOrdinarySpeechWithoutParsingTagsOrSpecialWords() throws Exception {
        RuntimeLanguage.configure("en_us");
        AtomicReference<String> heard = new AtomicReference<>();
        String speech = "@all keep this message intact\nNo command prefix is required.";
        Consumer<String> receiver = heard::set;
        ConversationDelivery.deliverSpeech("Player", speech, receiver);
        assertEquals("Player said: " + speech, heard.get());

        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("engine"))) root = root.getParent();
        String source = Files.readString(root.resolve("engine/src/main/java/com/mineagent/engine/MineAgentEngine.java"));
        int start = source.indexOf("public static void onPlayerChat(");
        String routing = source.substring(start, source.indexOf("    /**", start));
        assertTrue(routing.contains("ConversationDelivery.deliverSpeech"),
                "The game chat entry must use the tested natural-conversation boundary");
        assertFalse(routing.contains("startsWith(\"@\")"), "Player speech must not be intercepted as a tag protocol");
    }
}
