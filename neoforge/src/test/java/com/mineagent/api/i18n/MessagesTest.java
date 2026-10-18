package com.mineagent.api.i18n;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test
    void catalogsRequireEveryLocaleAndIdenticalNonblankKeysAndArguments() {
        assertThrows(IllegalArgumentException.class, () -> new MessageCatalog(java.util.Map.of(
                Language.EN_US, java.util.Map.of("key", "Text"))));
        assertThrows(IllegalArgumentException.class, () -> new MessageCatalog(java.util.Map.of(
                Language.EN_US, java.util.Map.of("key", "Text"),
                Language.PT_BR, java.util.Map.of("other", "Texto"))));
        assertThrows(IllegalArgumentException.class, () -> new MessageCatalog(java.util.Map.of(
                Language.EN_US, java.util.Map.of("key", "Text"),
                Language.PT_BR, java.util.Map.of("key", "  "))));
        assertThrows(IllegalArgumentException.class, () -> new MessageCatalog(java.util.Map.of(
                Language.EN_US, java.util.Map.of("key", "Text {0}"),
                Language.PT_BR, java.util.Map.of("key", "Texto"))));
        assertThrows(IllegalArgumentException.class,
                () -> Messages.text(Language.PT_BR, "tool.unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> Messages.text(Language.PT_BR, "tool.unknown", "x", "extra"));
        assertEquals("Ferramenta desconhecida: {0} %s $1 \\ minecraft:stone.",
                Messages.text(Language.PT_BR, "tool.unknown", "{0} %s $1 \\ minecraft:stone"));
    }

    @Test
    void catalogsAreImmutableAndRejectNoncontiguousOrNullEntriesWithUsefulDiagnostics() {
        var english = new java.util.HashMap<String, String>();
        english.put("key", "Text {0} {0}");
        MessageCatalog catalog = new MessageCatalog(java.util.Map.of(
                Language.EN_US, english, Language.PT_BR, java.util.Map.of("key", "Texto {0}")));
        english.put("key", "changed");
        assertEquals("Text $1 $1", catalog.text(Language.EN_US, "key", "$1"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.keys().clear());
        assertThrows(IllegalArgumentException.class, () -> new MessageCatalog(java.util.Map.of(
                Language.EN_US, java.util.Map.of("key", "Text {1}"),
                Language.PT_BR, java.util.Map.of("key", "Texto {1}"))));
        english.clear();
        english.put(null, "Text");
        assertThrows(IllegalArgumentException.class, () -> new MessageCatalog(java.util.Map.of(
                Language.EN_US, english, Language.PT_BR, java.util.Map.of("key", "Texto"))));
    }

    @Test
    void runtimeSelectionDoesNotChangeExplicitLanguageRendering() {
        try {
            RuntimeLanguage.configure("pt_br");
            assertEquals(Messages.text(Language.PT_BR, "model.language"), Messages.text("model.language"));
            assertTrue(Messages.text(Language.EN_US, "model.language").startsWith("Communicate in English."));
        } finally {
            RuntimeLanguage.configure("en_us");
        }
    }

    @Test
    void systemBoundaryUsesSelectedLanguageAndPreservesTechnicalArguments() {
        assertEquals("Communicate in English. Preserve technical identifiers and quoted external text exactly.",
                Messages.text(Language.EN_US, "model.language"));
        assertEquals("Comunique-se em português do Brasil. Preserve exatamente os identificadores técnicos e o texto externo citado.",
                Messages.text(Language.PT_BR, "model.language"));
        assertEquals("Ferramenta desconhecida: minecraft:stone.",
                Messages.text(Language.PT_BR, "tool.unknown", "minecraft:stone"));
        assertThrows(IllegalArgumentException.class,
                () -> Messages.text(Language.PT_BR, "missing.required.key"));
    }
}
