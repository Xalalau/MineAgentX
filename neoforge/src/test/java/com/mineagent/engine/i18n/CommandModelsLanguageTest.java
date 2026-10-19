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

    @Test void exampleModelListsPreserveOpaqueMetadataAndNulls() throws Exception {
        for (Language language : Language.values()) {
            fixture(language, (dispatcher, output, stack) -> {
                LLMProviderRegistry.register(provider("fixture", "Name {0} $1 \\ ç", java.util.Arrays.asList("model/{0}", null, "$1", "ç\\tail")));
                String heading = language == Language.PT_BR ? "Modelos de exemplo" : "Example Models";
                assertEquals(1, dispatcher.execute("mineagent models", stack));
                output.assertSuccess("§6[MineAgent] " + heading + ":\n\n§efixture§7 (Name {0} $1 \\ ç):§r\n"
                        + "  §7model/{0},   §7null,   §7$1, \n  §7ç\\tail\n");
                output.clear();
                LLMProviderRegistry.clear();
                LLMProviderRegistry.register(provider("fixture", null, java.util.Arrays.asList("model/{0}", null)));
                assertEquals(1, dispatcher.execute("mineagent models fixture", stack));
                output.assertSuccess(language == Language.PT_BR
                        ? "§6[MineAgent] Modelos de exemplo de §enull§r:\n  §7model/{0}\n  §7null\n§7Qualquer ID de modelo aceito por este endpoint pode ser informado diretamente."
                        : "§6[MineAgent] Example models for §enull§r:\n  §7model/{0}\n  §7null\n§7Any model ID accepted by this endpoint may be entered directly.");
            });
        }
    }

    static LLMProvider provider(String id, String name, List<String> models) {
        return new LLMProvider() {
            public String providerId() { return id; }
            public String displayName() { return name; }
            public String defaultBaseUrl() { return "http://127.0.0.1:8899/v1"; }
            public boolean requiresApiKey() { return false; }
            public boolean supportsModel(String model) { return true; }
            public List<String> defaultModels() { return models; }
            public LLMResponse complete(String url, String key, String model, List<ChatMessage> messages,
                    List<java.util.Map<String, Object>> tools, double temperature, int maxTokens, String effort) {
                throw new AssertionError("Command rendering must not call inference");
            }
        };
    }

    static void fixture(Language language, Check check) throws Exception {
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
    interface Check {
        void run(CommandDispatcher<CommandSourceStack> dispatcher, Capture output,
                 CommandSourceStack stack) throws Exception;
    }

    static final class Capture implements CommandSource {
        private final List<Component> messages = new ArrayList<>();
        private int successChecks;
        private int failureChecks;
        private int adminChecks;

        @Override public void sendSystemMessage(Component message) { messages.add(message); }
        @Override public boolean acceptsSuccess() { successChecks++; return true; }
        @Override public boolean acceptsFailure() { failureChecks++; return true; }
        // Returning true makes an accidental broadcast=true observable (null server fails).
        @Override public boolean shouldInformAdmins() { adminChecks++; return true; }

        List<String> strings() { return messages.stream().map(Component::getString).toList(); }

        void clear() { messages.clear(); successChecks = failureChecks = adminChecks = 0; }

        void assertSuccess(String... expected) {
            assertEquals(expected.length, successChecks);
            assertEquals(0, failureChecks);
            assertEquals(0, adminChecks);
            assertEquals(List.of(expected), messages.stream().map(Component::getString).toList());
            messages.forEach(message -> assertInstanceOf(PlainTextContents.class, message.getContents()));
        }

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
