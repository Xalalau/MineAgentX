package com.mineagent.tools.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure rendering policies can be exercised without bootstrapping Minecraft registries. */
class ToolOutputRenderingTest {
    @AfterEach void restoreLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void portugueseUsesExactTechnicalIdInsteadOfUntranslatedDefaultItemName() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("mod:Original_ITEM", ToolOutput.itemLabel("mod:Original_ITEM", "Untranslated default name", false));
    }

    @Test void customItemNamesRemainVerbatimInEitherLanguage() {
        String name = "Owner's item: {0} \"do not translate\" \\ $1\n";
        for (String language : new String[]{"en_us", "pt_br"}) {
            RuntimeLanguage.configure(language);
            assertEquals(name, ToolOutput.itemLabel("mod:item", name, true));
        }
    }

    @Test void englishKeepsObservedDefaultItemName() {
        RuntimeLanguage.configure("en_us");
        assertEquals("Observed resource name", ToolOutput.itemLabel("mod:item", "Observed resource name", false));
    }

    @Test void actualStatusAndScanTemplatesFormatInSelectedLanguage() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("Vida: 7.5/20.0\n", ToolOutput.formatted("status.health", 7.5, 20.0));
        assertEquals("Total: 3 entidades | Hostis: 1 | Jogadores em perigo: 0\n",
                ToolOutput.formatted("entities.counts", 3, 1, 0));
        RuntimeLanguage.configure("en_us");
        assertEquals("Health: 7.5/20.0\n", ToolOutput.formatted("status.health", 7.5, 20.0));
    }
}
