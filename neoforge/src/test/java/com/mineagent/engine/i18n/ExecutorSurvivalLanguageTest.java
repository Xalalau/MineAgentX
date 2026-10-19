package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.survival.CompanionBodyLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorSurvivalLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void survivalFormattingPreservesLiteralNamesAndQueuedHistory() {
        var log = new CompanionBodyLog();
        var received = new ArrayList<String>();
        log.setInboxForwarder(received::add);
        String literal = "Owner's {0} named food $1 / mod:apple";
        log.report("literal external history: eating an apple");
        log.report(ExecutorMessages.text("body.food.eating", literal));
        RuntimeLanguage.configure("pt_br");
        log.report(ExecutorMessages.text("body.food.eating", literal));
        log.report(ExecutorMessages.text("body.follow.route", "PATH_FAILED {0} -12,65,43"));
        assertEquals(4, log.pendingCount());
        log.flush();
        assertEquals(java.util.List.of("literal external history: eating an apple", "eating " + literal,
                "comendo " + literal, "não foi possível encontrar uma rota segura até o dono: PATH_FAILED {0} -12,65,43"), received);
        assertEquals(0, log.pendingCount());
        log.flush();
        assertEquals(4, received.size());
    }

    @Test void landingAndSurvivalEventsUseTheSelectedRuntimeLanguage() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("coloquei água abaixo de mim para amortecer a queda", ExecutorMessages.text("body.mlg.placed"));
        assertEquals("recuperei o balde de água após pousar", ExecutorMessages.text("body.mlg.recovered"));
        assertEquals("preciso comer, mas não tenho alimento seguro", ExecutorMessages.text("body.food.missing"));
        assertEquals("a alimentação foi interrompida antes de terminar", ExecutorMessages.text("body.food.interrupted"));
        assertEquals("alcancei o dono na dimensão dele", ExecutorMessages.text("body.follow.dimension"));
    }

    @Test void activeSurvivalProducersUseTheCatalogRatherThanRawEnglish() throws Exception {
        Path root = Path.of(System.getProperty("mineagent.projectRoot", "..")).toAbsolutePath();
        for (String name : new String[]{"MLGChain", "FoodChain", "FollowChain"}) {
            String source = Files.readString(root.resolve("engine/src/main/java/com/mineagent/engine/survival/" + name + ".java"));
            assertFalse(source.contains("bodyLog.report(\""), name);
            assertTrue(source.contains("ExecutorMessages.text(\"body."), name);
        }
    }
}
