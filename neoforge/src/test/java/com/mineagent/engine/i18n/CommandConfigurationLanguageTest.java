package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.MineAgentEngine;
import org.junit.jupiter.api.Test;

import static com.mineagent.engine.i18n.CommandModelsLanguageTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class CommandConfigurationLanguageTest {
    @Test void invalidKeysAndNumbers() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, output, stack) -> {
            assertEquals(0, dispatcher.execute("mineagent setconfig missing_key literal", stack));
            output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Chave desconhecida: missing_key" : "§c[MineAgent] Unknown key: missing_key",
                    language == Language.PT_BR ? "§7Válidas: provider, model, apikey, baseurl, temperature, name, skin" : "§7Valid: provider, model, apikey, baseurl, temperature, name, skin");
            output.clear();
            assertEquals(0, dispatcher.execute("mineagent setconfig temperature nope{0}$1", stack));
            output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Número inválido para temperature: nope{0}$1" : "§c[MineAgent] Invalid number for temperature: nope{0}$1");
            for (String value : new String[]{"-1", "3", "NaN", "Infinity"}) {
                output.clear();
                assertEquals(0, dispatcher.execute("mineagent setconfig temperature " + value, stack));
                output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] A temperatura deve estar entre 0 e 2." : "§c[MineAgent] Temperature must be from 0 to 2.");
            }
        });
    }

    @Test void updatesPreservePoliciesAndHideKeys() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, output, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                var before = MineAgentEngine.getConfig();
                for (String key : new String[]{"apikey", "api_key"}) {
                    output.clear();
                    assertEquals(1, dispatcher.execute("mineagent setconfig " + key + " fixture-secret", stack));
                    output.assertSuccess(language == Language.PT_BR ? "§a[MineAgent] Configuração atualizada: §f" + key + "§a = §f(oculta)" : "§a[MineAgent] Config updated: §f" + key + "§a = §f(hidden)");
                    assertEquals("fixture-secret", MineAgentEngine.getConfig().llm().apiKey());
                    assertEquals(before.safety(), MineAgentEngine.getConfig().safety());
                    assertEquals(before.vision(), MineAgentEngine.getConfig().vision());
                    assertEquals(before.reflection(), MineAgentEngine.getConfig().reflection());
                    assertEquals(before.language(), MineAgentEngine.getConfig().language());
                }
                output.clear();
                assertEquals(1, dispatcher.execute("mineagent setconfig model model/{0}:$1\\ç", stack));
                output.assertSuccess(language == Language.PT_BR ? "§a[MineAgent] Configuração atualizada: §fmodel§a = §fmodel/{0}:$1\\ç" : "§a[MineAgent] Config updated: §fmodel§a = §fmodel/{0}:$1\\ç");
            }
        });
    }

    @Test void reloadUsesServerLanguageAtRenderTime() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, output, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                var config = MineAgentEngine.getConfig().llm();
                assertEquals(1, dispatcher.execute("mineagent reload", stack));
                output.assertSuccess(language == Language.PT_BR ? "§a[MineAgent] Configuração recarregada!" : "§a[MineAgent] Configuration reloaded!",
                        (language == Language.PT_BR ? "§7Provedor: " : "§7Provider: ") + config.provider()
                                + (language == Language.PT_BR ? " | Modelo: " : " | Model: ") + config.model());
            }
        });
    }
}
