package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.memory.PlaceEventMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextMessagesTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void countsRemainNaturalWithoutInventingSingularOrPluralBehavior() {
        RuntimeLanguage.configure("en_us");
        assertEquals("Steps accepted for verification: 1", ContextMessages.text("skill.acceptedSteps", 1));
        RuntimeLanguage.configure("pt_br");
        assertEquals("Etapas aceitas para verificação: 1", ContextMessages.text("skill.acceptedSteps", 1));
    }

    @Test void placeholdersInsertIdsAndUntrustedStoredTextLiterallyInOnePass() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("A ferramenta auto_mine falhou: mod:ore {0} $1 \\ [KEEP]. Verifique os parâmetros e confirme o estado do alvo.",
                ContextMessages.text("reflection.toolFailed", "auto_mine", "mod:ore {0} $1 \\ [KEEP]"));
        assertThrows(IllegalArgumentException.class, () -> ContextMessages.text("raw observed text, not a key"));
        assertThrows(IllegalArgumentException.class, () -> ContextMessages.text("reflection.toolFailed", "missing argument"));
    }

    @Test void progressiveRecallHasExactLocalizedLayoutAndNeverDumpsOlderUnrequestedRecords() {
        var memory = new PlaceEventMemory();
        for (int i = 1; i <= 3; i++) memory.remember("resource", "mod:ore_" + i,
                i, 64, -i, "mod:moon", i, "literal {0} [KEEP]");
        RuntimeLanguage.configure("pt_br");
        assertEquals("## Memórias de locais (recursos/eventos vistos recentemente)\n"
                + "- mod:ore_3 em (3,64,-3) [mod:moon] — literal {0} [KEEP]\n"
                + "- mod:ore_2 em (2,64,-2) [mod:moon] — literal {0} [KEEP]\n"
                + "(0 chunks explorados)\n", memory.summarizeForPrompt("", 2));
        assertEquals("", memory.summarizeForPrompt("", 0));
        assertEquals("", memory.summarizeForPrompt("unrelated", 2));
    }
}
