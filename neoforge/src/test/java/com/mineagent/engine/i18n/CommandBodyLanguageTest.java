package com.mineagent.engine.i18n;

import com.mineagent.api.entity.CompanionGameMode;
import com.mineagent.api.i18n.Language;
import com.mineagent.engine.entity.CompanionLifecycleHandler;
import org.junit.jupiter.api.Test;

import static com.mineagent.engine.i18n.CommandModelsLanguageTest.fixture;
import static org.junit.jupiter.api.Assertions.*;

class CommandBodyLanguageTest {
    @Test void missingCompanionReplies() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                for (String command : new String[]{"remove", "respawn", "mode", "mode follow", "locate", "tp", "setskin default", "resetskin", "rename other"}) {
                    body.output.clear();
                    assertEquals(0, body.dispatcher.execute("mineagent " + command, body.stack));
                    boolean period = command.equals("remove") || command.equals("respawn");
                    body.output.assertFailure((language == Language.PT_BR ? "§c[MineAgent] Você não tem um companheiro" : "§c[MineAgent] You don't have a companion") + (period ? "." : "!"));
                }
                body.output.clear();
                assertEquals(0, body.dispatcher.execute("mineagent mode missing_name", body.stack));
                body.output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Nenhum companheiro chamado 'missing_name' foi encontrado." : "§c[MineAgent] No companion named 'missing_name' found.");
            }
        });
    }

    @Test void guardedBodyBranches() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                var state = body.companion("fixture");
                assertEquals(0, body.dispatcher.execute("mineagent respawn", body.stack));
                body.output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Seu companheiro não está morto." : "§c[MineAgent] Your companion is not dead.");
                body.output.clear();
                assertEquals(0, body.dispatcher.execute("mineagent mode fixture invalid_mode", body.stack));
                body.output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Modo inválido 'invalid_mode'. Use follow ou free." : "§c[MineAgent] Invalid mode 'invalid_mode'. Use follow or free.");
                body.output.clear();
                assertEquals(0, body.dispatcher.execute("mineagent setskin username_too_long_123", body.stack));
                body.output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] A skin deve usar um nome de jogador válido do Minecraft." : "§c[MineAgent] Skin name must be a valid Minecraft username.");
                CommandPlayerFixture.set(state.lifecycle, CompanionLifecycleHandler.class, "dead", true);
                state.companion.setGameMode(CompanionGameMode.HARDCORE);
                body.output.clear();
                assertEquals(0, body.dispatcher.execute("mineagent respawn", body.stack));
                body.output.assertFailure(language == Language.PT_BR ? "§c[MineAgent] Este companheiro morreu no modo Hardcore e não pode reviver. Crie um novo companheiro." : "§c[MineAgent] This companion died in Hardcore mode and cannot respawn. Create a new companion instead.");
            }
        });
    }

    @Test void companionListKeepsNamesAndFlags() throws Exception {
        for (Language language : Language.values()) fixture(language, (dispatcher, ignored, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                var state = body.companion("Name {0} $1 \\ ç");
                CommandPlayerFixture.set(state.lifecycle, CompanionLifecycleHandler.class, "dead", true);
                CommandPlayerFixture.set(state.lifecycle, CompanionLifecycleHandler.class, "paused", true);
                assertEquals(1, body.dispatcher.execute("mineagent list", body.stack));
                body.output.assertSuccess(language == Language.PT_BR
                        ? "§7[MineAgent] Companheiros ativos:\n§7  - Name {0} $1 \\ ç (dono: Owner {0} $1 \\ ç) §c[MORTO] §e[PAUSADO]\n"
                        : "§7[MineAgent] Active companions:\n§7  - Name {0} $1 \\ ç (owner: Owner {0} $1 \\ ç) §c[DEAD] §e[PAUSED]\n");
            }
        });
    }
}
