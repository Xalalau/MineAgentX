package com.mineagent.api.i18n;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class LanguageTest {
    @Test
    void languageIdsAreExplicitAndIndependentOfTheJvmLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"));
            assertEquals("en_us", RuntimeLanguage.language().id());
            assertEquals(Language.PT_BR, Language.fromId("pt_br"));
            RuntimeLanguage.configure("pt_br");
            assertEquals(Language.PT_BR, RuntimeLanguage.language());
            assertThrows(IllegalArgumentException.class, () -> RuntimeLanguage.configure("pt-br"));
            assertEquals(Language.PT_BR, RuntimeLanguage.language());
            assertThrows(IllegalArgumentException.class, () -> Language.fromId("fr_fr"));
            assertThrows(IllegalArgumentException.class, () -> Language.fromId(null));
        } finally {
            RuntimeLanguage.configure("en_us");
            Locale.setDefault(original);
        }
    }
}
