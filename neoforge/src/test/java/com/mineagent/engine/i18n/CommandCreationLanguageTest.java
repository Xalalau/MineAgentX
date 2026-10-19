package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.llm.provider.LLMProviderRegistry;
import com.mineagent.engine.MineAgentEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mineagent.engine.i18n.CommandModelsLanguageTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandCreationLanguageTest {
    @Test void effortCommandUsesLocalizedPolicyWithoutChangingAdmission() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                for (String command : List.of("quick fixture high", "seteffort high", "seteffort fixture high")) {
                    body.output.clear();
                    assertEquals(0, body.dispatcher.execute("mineagent " + command, body.stack));
                    body.output.assertFailure(language == Language.PT_BR
                            ? "§c[MineAgent] O raciocínio local é gerenciado pelo servidor; use auto ou deixe o campo vazio."
                            : "§c[MineAgent] Local reasoning is server-managed; use auto or leave the field empty.");
                }
                body.output.clear();
                assertEquals(0, body.dispatcher.execute("mineagent seteffort auto", body.stack));
                body.output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Companheiro não encontrado!" : "§c[MineAgent] Companion not found!");
            }
        });
    }

    @Test void realSpawnValidationReasonsAreLocalizedBeforeWorldMutation() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                LLMProviderRegistry.register(provider("fixture", "Fixture", List.of()));
                String[][] cases = {
                        {"", "fixture", "key", "model", "http://127.0.0.1", "0.5", "auto", "name must be 1-64 printable characters", "o nome deve ter de 1 a 64 caracteres imprimíveis"},
                        {"fixture", null, "key", "model", "http://127.0.0.1", "0.5", "auto", "unknown LLM provider 'null'", "provedor de LLM desconhecido 'null'"},
                        {"fixture", "fixture", "x".repeat(16385), "model", "http://127.0.0.1", "0.5", "auto", "API key is too long", "a chave de API é longa demais"},
                        {"fixture", "fixture", "key", "", "http://127.0.0.1", "0.5", "auto", "model must be 1-256 characters", "o modelo deve ter de 1 a 256 caracteres"},
                        {"fixture", "fixture", "key", "model", "http://127.0.0.1", "NaN", "auto", "temperature must be a finite number from 0 to 2", "a temperatura deve ser um número finito entre 0 e 2"},
                        {"fixture", "fixture", "key", "model", "x".repeat(2049), "0.5", "auto", "base URL is too long", "a URL base é longa demais"},
                        {"fixture", "fixture", "key", "model", "http://[", "0.5", "auto", "openai-compatible base URL is malformed", "a URL base de openai-compatible está malformada"},
                        {"fixture", "fixture", "key", "model", "file:///local", "0.5", "auto", "openai-compatible base URL must be an absolute HTTP(S) URL without query or fragment", "a URL base de openai-compatible deve ser uma URL HTTP(S) absoluta sem credenciais, consulta ou fragmento"},
                        {"fixture", "fixture", "key", "model", "http://8.8.8.8", "0.5", "auto", "openai-compatible requires a loopback or private LAN server; public endpoints are disabled", "openai-compatible exige um servidor loopback ou de rede local privada; endpoints públicos estão desativados"},
                        {"fixture", "fixture", "key", "model", "http://127.0.0.1", "0.5", "high", "Local reasoning is server-managed; use auto or leave the field empty.", "O raciocínio local é gerenciado pelo servidor; use auto ou deixe o campo vazio."}
                };
                for (String[] row : cases) {
                    body.player.messages.clear();
                    assertNull(MineAgentEngine.spawnCompanion(body.player, row[0], row[1], row[2], row[3], row[4], Double.parseDouble(row[5]), row[6], false));
                    assertEquals(List.of((language == Language.PT_BR ? "§c[MineAgent] Não foi possível criar o companheiro: " : "§c[MineAgent] Cannot spawn companion: ") + row[language == Language.PT_BR ? 8 : 7]), body.player.messages);
                    assertTrue(CommandPlayerFixture.map("COMPANIONS").isEmpty());
                }
                body.player.fixtureServer = null;
                body.player.messages.clear();
                assertNull(MineAgentEngine.spawnCompanion(body.player, "fixture", "fixture", "", "model", null, 0.5, "auto", false));
                assertEquals(List.of(language == Language.PT_BR ? "§c[MineAgent] Não foi possível criar o companheiro: o dono não está conectado" : "§c[MineAgent] Cannot spawn companion: owner is not connected"), body.player.messages);
            }
        });
    }

    @Test void setupRejectsUnknownGameModeWithoutChangingConfiguration() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                var previous = MineAgentEngine.getConfig();
                assertNull(MineAgentEngine.configureAndSpawnCompanion(body.player, "fixture", "fixture", "", false,
                        "model", "http://127.0.0.1", 0.5, "auto", "unknown_mode"));
                assertSame(previous, MineAgentEngine.getConfig());
                assertEquals(List.of(language == Language.PT_BR ? "§c[MineAgent] Não foi possível criar o companheiro: modo de jogo inválido." : "§c[MineAgent] Cannot spawn companion: invalid game mode."), body.player.messages);
            }
        });
    }

    @Test void spawnCommandKeepsExistingReturnAndOpaqueProvider() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                assertEquals(1, body.dispatcher.execute("mineagent spawn fixture missing_provider model key", body.stack));
                assertTrue(body.output.strings().isEmpty());
                assertEquals(List.of(language == Language.PT_BR
                        ? "§c[MineAgent] Não foi possível criar o companheiro: provedor de LLM desconhecido 'missing_provider'"
                        : "§c[MineAgent] Cannot spawn companion: unknown LLM provider 'missing_provider'"), body.player.messages);
            }
        });
    }
}
