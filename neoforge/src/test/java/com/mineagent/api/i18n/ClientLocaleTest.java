package com.mineagent.api.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClientLocaleTest {
    @Test
    void englishAndPortugueseUiCatalogsHaveIdenticalNonblankKeysAndFormatArguments() throws Exception {
        Path root = Path.of(System.getProperty("mineagentx.projectRoot", "."));
        Path locales = root.resolve("engine/src/client/resources/assets/mineagent/lang");
        assertTrue(Files.isRegularFile(locales.resolve("pt_br.json")), "Portuguese UI catalog is required");
        JsonObject english = JsonParser.parseString(Files.readString(locales.resolve("en_us.json"))).getAsJsonObject();
        JsonObject portuguese = JsonParser.parseString(Files.readString(locales.resolve("pt_br.json"))).getAsJsonObject();
        assertEquals(english.keySet(), portuguese.keySet());
        for (String key : english.keySet()) {
            String value = portuguese.get(key).getAsString();
            assertFalse(value.isBlank(), key);
            long englishArguments = english.get(key).getAsString().chars().filter(c -> c == '%').count();
            assertEquals(englishArguments, value.chars().filter(c -> c == '%').count(), key);
        }
        assertEquals("/mineagent remove - remove all companions owned by you",
                english.get("screen.mineagent.help.commands.remove").getAsString());
        assertEquals("/mineagent remove - remover todos os seus companheiros",
                portuguese.get("screen.mineagent.help.commands.remove").getAsString());
        assertEquals("Enviar", portuguese.get("screen.mineagent.chat.send").getAsString());
        assertTrue(portuguese.get("screen.mineagent.help.commands.quick").getAsString()
                .startsWith("/mineagent quick [name] [auto]"));
    }
}
