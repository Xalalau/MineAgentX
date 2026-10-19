package com.mineagent.engine.i18n;

import com.mineagent.api.config.MineAgentConfig;
import com.mineagent.api.i18n.Language;
import com.mineagent.api.llm.provider.LLMProviderRegistry;
import com.mineagent.engine.MineAgentEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mineagent.engine.i18n.CommandModelsLanguageTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandInformationLanguageTest {
    @Test void providersKeepLiteralMetadata() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, output, stack) -> {
            LLMProviderRegistry.register(provider("fixture", null, List.of()));
            assertEquals(1, dispatcher.execute("mineagent providers", stack));
            output.assertSuccess(language == Language.PT_BR
                    ? "§6[MineAgent] Provedores de LLM disponíveis:\n§e  - fixture§7 (null)§r\n\n§7Use §e/mineagent models [provider]§7 para ver IDs de modelos de exemplo.\n§7Adaptadores compatíveis também aceitam IDs de modelos não listados aqui."
                    : "§6[MineAgent] Available LLM Providers:\n§e  - fixture§7 (null)§r\n\n§7Use §e/mineagent models [provider]§7 to see example model IDs.\n§7Compatible adapters also accept model IDs not listed here.");
        });
    }

    @Test void emptyListKeepsReturnCode() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, output, stack) -> {
            assertEquals(0, dispatcher.execute("mineagent list", stack));
            output.assertSuccess(language == Language.PT_BR
                    ? "§7[MineAgent] Nenhum companheiro ativo." : "§7[MineAgent] No active companions.");
        });
    }

    @Test void configLabelsAndSecrets() throws Exception {
        var field = MineAgentEngine.class.getDeclaredField("config");
        field.setAccessible(true);
        var previous = MineAgentEngine.getConfig();
        try {
            for (Language language : Language.values()) for (String secret : List.of("", "fixture-secret-never-render")) {
                var llm = new MineAgentConfig.LLMConfig("fixture", secret, "model/{0}:$1\\ç", "", 0.5, 123);
                field.set(null, new MineAgentConfig(llm, previous.companion(), previous.survival(), previous.pathfinding(),
                        language.id(), previous.safety(), previous.vision(), previous.reflection()));
                fixture(language, (dispatcher, output, stack) -> {
                    assertEquals(1, dispatcher.execute("mineagent config", stack));
                    String value = output.strings().getFirst();
                    assertFalse(value.contains("fixture-secret-never-render"));
                    assertTrue(value.contains("model/{0}:$1\\ç"));
                    assertTrue(value.contains("config/mineagent.json"));
                    if (language == Language.PT_BR) {
                        assertTrue(value.startsWith("§6[MineAgent] Configuração atual:\n"));
                        assertTrue(value.contains("Provedor: §ffixture"));
                        assertTrue(value.contains("Modelo: §fmodel/"));
                        assertTrue(value.contains("Chave de API: §f" + (secret.isEmpty() ? "§c(não definida)" : "****(oculta)")));
                        assertTrue(value.contains("URL base: §f(padrão)"));
                        assertTrue(value.contains("Temperatura: §f0.5"));
                        assertTrue(value.contains("Máximo de tokens: §f123"));
                        assertTrue(value.contains("§eCompanheiro:\n"));
                        assertTrue(value.contains("Nome: §f"));
                        assertTrue(value.contains("Modo de jogo: §f"));
                        assertTrue(value.contains("Arquivo de configuração: config/mineagent.json"));
                    } else {
                        assertTrue(value.startsWith("§6[MineAgent] Current Configuration:\n"));
                        assertTrue(value.contains(secret.isEmpty() ? "§c(not set)" : "****(hidden)"));
                    }
                });
            }
        } finally { field.set(null, previous); }
    }

    @Test void helpDescribesActualGrammarAndScope() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, output, stack) -> {
            assertEquals(1, dispatcher.execute("mineagent", stack));
            String help = output.strings().getFirst();
            output.clear();
            assertEquals(1, dispatcher.execute("mineagent help", stack));
            output.assertSuccess(help);
            assertTrue(help.contains("/mineagent quick§7 [name] [auto]"));
            assertTrue(help.contains("<name> <provider> <model> <apikey> [temp]"));
            assertTrue(help.contains("[name] <auto>"));
            assertTrue(help.contains("config/mineagent.json"));
            if (language == Language.PT_BR) {
                assertTrue(help.contains("Comandos do MineAgent"));
                assertTrue(help.contains("Remover todos os seus companheiros"));
                assertTrue(help.contains("fora do modo Hardcore"));
                assertTrue(help.contains("IDs de modelos de exemplo"));
            } else {
                assertTrue(help.contains("Remove all your companions"));
                assertTrue(help.contains("outside Hardcore mode"));
                assertTrue(help.contains("List example model IDs"));
            }
            assertEquals(Set.of("help", "quick", "spawn", "remove", "respawn", "list", "providers", "models",
                    "config", "mode", "locate", "tp", "reload", "setskin", "resetskin", "setconfig", "rename", "seteffort"),
                    dispatcher.getRoot().getChild("mineagent").getChildren().stream()
                            .map(node -> node.getName()).collect(Collectors.toSet()));
        });
    }
}
