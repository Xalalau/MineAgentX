package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderRegistry;
import com.mineagent.engine.MineAgentEngine;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandModelsLanguageTest {
    @Test void englishUnknownProvider() throws Exception {
        fixture(Language.EN_US, (dispatcher, output, stack) -> {
            assertEquals(0, dispatcher.execute("mineagent models missing_provider-1.2", stack));
            output.assertFailure(
                    "§c[MineAgent] Unknown provider: missing_provider-1.2",
                    "§7Use §e/mineagent providers§7 to see available providers.");
        });
    }

    @Test void portugueseUnknownProvider() throws Exception {
        fixture(Language.PT_BR, (dispatcher, output, stack) -> {
            assertEquals(0, dispatcher.execute("mineagent models missing_provider-1.2", stack));
            output.assertFailure(
                    "§c[MineAgent] Provedor desconhecido: missing_provider-1.2",
                    "§7Use §e/mineagent providers§7 para ver os provedores disponíveis.");
        });
    }

    private static void fixture(Language language, Check check) throws Exception {
        Language previousLanguage = RuntimeLanguage.language();
        List<LLMProvider> previousProviders = new ArrayList<>(LLMProviderRegistry.all());
        try {
            LLMProviderRegistry.clear();
            RuntimeLanguage.configure(language.id());
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            MineAgentEngine.registerCommands(dispatcher);
            Capture output = new Capture();
            CommandSourceStack stack = new CommandSourceStack(output, Vec3.ZERO, Vec2.ZERO,
                    null, 0, "fixture", Component.literal("fixture"), null, null);
            assertNull(stack.getLevel());
            assertNull(stack.getServer());
            assertNull(stack.getPlayer());
            check.run(dispatcher, output, stack);
        } finally {
            LLMProviderRegistry.clear();
            previousProviders.forEach(LLMProviderRegistry::register);
            RuntimeLanguage.configure(previousLanguage.id());
        }
    }

    @FunctionalInterface
    private interface Check {
        void run(CommandDispatcher<CommandSourceStack> dispatcher, Capture output,
                 CommandSourceStack stack) throws Exception;
    }

    private static final class Capture implements CommandSource {
        private final List<Component> messages = new ArrayList<>();
        private int successChecks;
        private int failureChecks;
        private int adminChecks;

        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { successChecks++; return true; }
        @Override public boolean acceptsFailure() { failureChecks++; return true; }
        // Returning true makes an accidental broadcast=true observable (null server fails).
        @Override public boolean shouldInformAdmins() { adminChecks++; return true; }

        void assertFailure(String... expected) {
            assertEquals(0, successChecks);
            assertEquals(expected.length, failureChecks);
            assertEquals(0, adminChecks);
            assertEquals(List.of(expected), messages.stream().map(Component::getString).toList());
            for (int i = 0; i < expected.length; i++) {
                Component wrapped = messages.get(i);
                assertEquals(net.minecraft.ChatFormatting.RED, wrapped.getStyle().getColor() == null
                        ? null : net.minecraft.ChatFormatting.getByName(wrapped.getStyle().getColor().serialize()));
                assertEquals(1, wrapped.getSiblings().size());
                Component payload = wrapped.getSiblings().getFirst();
                assertEquals(expected[i], assertInstanceOf(PlainTextContents.class, payload.getContents()).text());
                assertTrue(payload.getSiblings().isEmpty());
            }
        }
    }
}
