package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;

import java.util.Map;

/** Server-owned command prose; metadata is inserted literally by the strict catalog. */
public final class CommandMessages {
    private static final MessageCatalog CATALOG = new MessageCatalog(Map.of(
            Language.EN_US, Map.of(
                    "models.unknown_provider", "§c[MineAgent] Unknown provider: {0}",
                    "models.providers_hint", "§7Use §e/mineagent providers§7 to see available providers."),
            Language.PT_BR, Map.of(
                    "models.unknown_provider", "§c[MineAgent] Provedor desconhecido: {0}",
                    "models.providers_hint", "§7Use §e/mineagent providers§7 para ver os provedores disponíveis.")));

    private CommandMessages() {}

    public static String text(String key, Object... arguments) {
        return CATALOG.text(RuntimeLanguage.language(), key, arguments);
    }
}
