package com.mineagent.engine.identity;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.llm.*;
import com.mineagent.api.llm.provider.*;
import com.mineagent.api.platform.Services;
import com.mineagent.engine.cognition.SituationSnapshot;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.loop.AgentLoop;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real tick/turn/request consumer, controlled executor and previously published world frame. No world simulation. */
final class IdentityIdleFixture implements AutoCloseable {
    static final AtomicInteger REQUEST_IDS = new AtomicInteger();
    final String language = RuntimeLanguage.language().id();
    final List<LLMProvider> providers = new ArrayList<>(LLMProviderRegistry.all());
    final Object platform = field(Services.class, "PLATFORM");
    final Queue<Runnable> server = new ConcurrentLinkedQueue<>();
    final ControlledExecutor executor = new ControlledExecutor();
    final Body body;
    final AgentLoop loop;
    final List<LLMRequest> requests = new ArrayList<>();
    Runnable duringRequest = () -> {};
    boolean immediateServer;
    Map<UUID, com.mineagent.engine.MineAgentEngine.CompanionState> companions;
    com.mineagent.engine.MineAgentEngine.CompanionState previousCompanion;
    void registerLoop() {
        @SuppressWarnings("unchecked") var map=(Map<UUID, com.mineagent.engine.MineAgentEngine.CompanionState>) field(com.mineagent.engine.MineAgentEngine.class,"COMPANIONS");
        companions=map;
        previousCompanion=companions.put(body.companionId(),new com.mineagent.engine.MineAgentEngine.CompanionState(body,loop,null,null));
    }
    java.util.function.Supplier<ChatMessage> response = () -> ChatMessage.assistant("");
    IdentityIdleFixture(String language) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        RuntimeLanguage.configure(language);
        set(Services.class, "PLATFORM", new Services.IPlatform() {
            public void scheduleOnServer(Runnable task) { if (immediateServer) task.run(); else server.add(task); }
            public Optional<UUID> findPlayerUuid(String name) { return Optional.empty(); }
            public boolean isPlayerOnline(UUID id) { return false; }
            public boolean isClientSide() { return false; }
            public long gameTime() { return 100; }
        });
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        body = new Body((QuietPlayer) unsafe.allocateInstance(QuietPlayer.class));
        loop = new AgentLoop(body, "identity-idle-fixture", "", "fixture", "http://127.0.0.1", 0, 100, "auto");
        ((ExecutorService) field(loop, "executor")).shutdownNow();
        set(loop, "executor", executor);
        LLMProviderRegistry.register(new LLMProvider() {
            public String providerId() { return "identity-idle-fixture"; }
            public String displayName() { return "fixture"; }
            public String defaultBaseUrl() { return "http://127.0.0.1"; }
            public boolean supportsModel(String model) { return true; }
            public List<String> defaultModels() { return List.of(); }
            public LLMResponse complete(String url, String key, String model, List<ChatMessage> messages,
                    List<Map<String,Object>> tools, double temperature, int maxTokens, String effort) {
                throw new AssertionError("request overload required");
            }
            public LLMResponse complete(LLMRequest value) {
                requests.add(value);
                String dir = System.getProperty("identityIdle.traceDir");
                if (dir != null) try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "request-" + REQUEST_IDS.incrementAndGet() + ".json"),
                            new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(
                                    Map.of("language", RuntimeLanguage.language().id(), "messages", value.messages(),
                                            "fallback_messages", value.fallbackMessages(), "tools", value.tools())));
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                duringRequest.run();
                return new LLMResponse("fixture", "fixture", new LLMResponse.Choice(0, response.get(), "stop"), null, "stop");
            }
        });
    }
    EvolvingIdentity.Entry goal(String subject, String statement) {
        var id = loop.identity().recordEvidence("observation", "fixture", "evidence for " + subject, 1).id();
        return loop.identity().revise("goal", subject, statement, "Observed reason {literal}", List.of(id), .7, 0, "");
    }
    void tick(long tick) throws Exception {
        // Use the real observer's bounded-cadence branch with an immutable, previously published frame.
        set(loop, "lastAssetSnapshotTick", tick);
        ((AtomicLong) field(loop, "liveAssetGameTick")).set(tick);
        var cognition = loop.realtimeCognition();
        set(cognition, "lastObservationTick", tick);
        @SuppressWarnings("unchecked") var frame = (AtomicReference<SituationSnapshot>) field(cognition, "latestFrame");
        frame.set(new SituationSnapshot(tick, null, null, null, null, null, List.of()));
        loop.onServerStateTick(tick);
    }
    void run() { executor.runAll(); }
    String sent() { return requests.getLast().messages().stream().map(ChatMessage::content).filter(Objects::nonNull).reduce("", (a,b) -> a+"\n"+b); }
    static Object field(Object target, String name) {
        try { var f = (target instanceof Class<?> c ? c : target.getClass()).getDeclaredField(name); f.setAccessible(true); return f.get(target instanceof Class<?> ? null : target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static void set(Object target, String name, Object value) {
        try { var f = (target instanceof Class<?> c ? c : target.getClass()).getDeclaredField(name); f.setAccessible(true); f.set(target instanceof Class<?> ? null : target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static Object invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, values);
    }
    public void close() {
        executor.shutdownNow();
        loop.realtimeCognition().close();
        if (companions != null) {
            if (previousCompanion == null) companions.remove(body.companionId());
            else companions.put(body.companionId(),previousCompanion);
        }
        set(Services.class, "PLATFORM", platform);
        LLMProviderRegistry.clear(); providers.forEach(LLMProviderRegistry::register);
        RuntimeLanguage.configure(language);
        Thread.interrupted();
    }
    static final class ControlledExecutor extends AbstractExecutorService {
        final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        boolean stopped;
        boolean reject;
        int submissions;
        public void execute(Runnable command) { submissions++; if (stopped || reject) throw new RejectedExecutionException("fixture"); queue.add(command); }
        void runAll() { int count=0; while (!queue.isEmpty()) { if (++count>20) throw new AssertionError("turn storm"); queue.remove().run(); } }
        public void shutdown() { stopped=true; }
        public List<Runnable> shutdownNow() { stopped=true; var pending=new ArrayList<>(queue); queue.clear(); return pending; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && queue.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
    static final class Body extends CompanionEntity {
        volatile boolean alive=true;
        Runnable contextCallback;
        Body(ServerPlayer player) { super(player, null, "fixture"); }
        public UUID companionId() { var callback=contextCallback; contextCallback=null; if(callback!=null) callback.run(); return new UUID(0, 11); }
        public UUID ownerUuid() { return new UUID(0, 12); }
        public String ownerName() { return "fixture owner"; }
        public String blockPositionStr() { return "0,64,0"; }
        public double posX() { return 0; } public double posY() { return 64; } public double posZ() { return 0; }
        public float health() { return 20; } public float maxHealth() { return 20; }
        public int foodLevel() { return 20; } public int airSupply() { return 300; }
        public boolean isInWater() { return false; } public boolean isAlive() { return alive; }
        public void holdInHand(int slot) { }
        public String mainHandItemId() { return "minecraft:air"; }
        public String dimensionKey() { return "minecraft:overworld"; }
    }
    static final class QuietPlayer extends ServerPlayer {
        private QuietPlayer() { super(null, null, null, null); }
        public boolean isInLava() { return false; }
        public boolean isOnFire() { return false; }
        public boolean isInWater() { return false; }
    }
}
