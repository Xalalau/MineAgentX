package com.mineagent.api.i18n;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LocalizedMessageTest {
    @Test
    void durableMessagesStoreKeysAndLiteralArgumentsNotPreviouslyRenderedEnglish() {
        var arguments = new ArrayList<>(List.of("minecraft:stone {0} %s"));
        LocalizedMessage message = new LocalizedMessage("result", arguments);
        arguments.set(0, "changed");
        MessageCatalog catalog = new MessageCatalog(Map.of(
                Language.EN_US, Map.of("result", "Observed {0}."),
                Language.PT_BR, Map.of("result", "Observado: {0}.")));
        Gson gson = new Gson();
        LocalizedMessage restored = gson.fromJson(gson.toJson(message), LocalizedMessage.class);
        assertEquals("Observed minecraft:stone {0} %s.", restored.render(Language.EN_US, catalog));
        assertEquals("Observado: minecraft:stone {0} %s.", restored.render(Language.PT_BR, catalog));
        assertThrows(UnsupportedOperationException.class, () -> message.arguments().add("x"));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedMessage(" ", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LocalizedMessage("missing", List.of()).render(Language.PT_BR, catalog));
    }
}
