package com.mineagent.engine.conversation;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.platform.Services;
import com.mineagent.api.llm.*;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Production loop, real request assembly, recording provider; no game/server or model. */
final class ConversationRuntimeFixture implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicInteger REQUESTS = new java.util.concurrent.atomic.AtomicInteger();
    final java.lang.reflect.Field platformField;
    final Object oldPlatform;
    final ArrayDeque<Runnable> server = new ArrayDeque<>();
    final AgentLoop loop;
    final TestBody body = new TestBody();
    final String oldLanguage = RuntimeLanguage.language().id();
    Map<UUID, MineAgentEngine.CompanionState> companions;
    MineAgentEngine.CompanionState previousCompanion;
    LLMRequest request;
    Runnable duringRequest = () -> {};
    ConversationRuntimeFixture() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        platformField = Services.class.getDeclaredField("PLATFORM"); platformField.setAccessible(true);
        oldPlatform = platformField.get(null);
        platformField.set(null, new Services.IPlatform() {
            public void scheduleOnServer(Runnable task) { server.add(task); }
            public Optional<UUID> findPlayerUuid(String name) { return Optional.empty(); }
            public boolean isPlayerOnline(UUID id) { return false; }
            public boolean isClientSide() { return false; }
            public long gameTime() { return 2; }
        });
        loop = new AgentLoop(body, "ollama", "", "fixture", "http://127.0.0.1", 0, 100, "auto");
        set(loop, "inProgress", true); // Test controls draining/request without starting executor work.
    }
    Object recordRequest() throws Exception {
        invoke(loop, "drainInbox", new Class<?>[0]);
        LLMProvider provider = new LLMProvider() {
            public String providerId() { return "ollama"; }
            public String displayName() { return "fixture"; }
            public String defaultBaseUrl() { return "http://127.0.0.1"; }
            public boolean supportsModel(String model) { return true; }
            public List<String> defaultModels() { return List.of(); }
            public LLMResponse complete(String url, String key, String model, List<ChatMessage> messages,
                    List<Map<String,Object>> tools, double temperature, int maxTokens, String effort) {
                throw new AssertionError("request overload required");
            }
            public LLMResponse complete(LLMRequest value) {
                request = value; duringRequest.run();
                String traceDir = System.getProperty("conversationBoundary.traceDir");
                if (traceDir != null) {
                    var artifact = Map.of("language", RuntimeLanguage.language().id(), "model", value.model(),
                            "messages", value.messages(), "fallback_messages", value.fallbackMessages(),
                            "tools", value.tools(), "deadline_seconds", value.deadline().toSeconds());
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(traceDir, "request-" + REQUESTS.incrementAndGet() + ".json"),
                                new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(artifact));
                    } catch (java.io.IOException error) { throw new AssertionError(error); }
                }
                return new LLMResponse("fixture", "fixture", new LLMResponse.Choice(0, ChatMessage.assistant("fixture"), "stop"), null, "stop");
            }
        };
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> definitions = (List<Map<String,Object>>) invoke(loop, "buildToolDefinitions", new Class<?>[0]);
        return invoke(loop, "callLLMWithRetry", new Class<?>[]{LLMProvider.class, List.class, int.class}, provider, definitions, 0);
    }
    String sent() { return request.messages().stream().map(ChatMessage::content).filter(Objects::nonNull).reduce("", (a,b) -> a+"\n"+b); }
    void duringContextBuild(Runnable callback) { body.contextCallback = callback; }
    AgentPlayer body() { return body; }
    @SuppressWarnings("unchecked")
    void installAuction(com.mineagent.engine.scheduler.PriorityAuction auction) throws Exception {
        var field = MineAgentEngine.class.getDeclaredField("COMPANIONS");
        field.setAccessible(true);
        companions = (Map<UUID, MineAgentEngine.CompanionState>) field.get(null);
        previousCompanion = companions.put(body.companionId(), new MineAgentEngine.CompanionState(null, loop, auction, null));
    }
    static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    static void set(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    static Object invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        var m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, values);
    }
    public void close() throws Exception {
        ((ExecutorService) field(loop, "executor")).shutdownNow();
        platformField.set(null, oldPlatform);
        RuntimeLanguage.configure(oldLanguage);
        if (companions != null) {
            if (previousCompanion == null) companions.remove(body.companionId());
            else companions.put(body.companionId(), previousCompanion);
        }
        Thread.interrupted();
    }
    private static final class TestBody extends AgentPlayer {
        Runnable contextCallback;
        public UUID companionId() {
            Runnable callback = contextCallback;
            contextCallback = null;
            if (callback != null) callback.run();
            return new UUID(0, 1);
        }
        public UUID ownerUuid() { return new UUID(0, 2); }
        public String companionName() { return "test companion"; }
        public String ownerName() { return "test owner"; }
        public String blockPositionStr() { return "0,0,0"; }
        public double posX() { return 0; } public double posY() { return 0; } public double posZ() { return 0; }
        public float health() { return 20; } public float maxHealth() { return 20; }
        public int foodLevel() { return 20; } public int airSupply() { return 300; }
        public boolean isInWater() { return false; } public boolean isAlive() { return true; }
        public void holdInHand(int slot) { }
        public String mainHandItemId() { return "minecraft:air"; }
        public String dimensionKey() { return "minecraft:overworld"; }
    }
}
