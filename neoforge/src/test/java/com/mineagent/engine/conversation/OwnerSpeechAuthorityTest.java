package com.mineagent.engine.conversation;

import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.execution.ExecutionOwner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.mineagent.engine.conversation.ConversationRuntimeFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class OwnerSpeechAuthorityTest {
    static final Tool TOOL = new Tool() {
        public String name() { return "fixture_action"; }
        public String description() { return "fixture"; }
        public Map<String,Object> parameterSchema() { return Map.of(); }
        public void onServerCall(String id, com.google.gson.JsonObject args, com.mineagent.api.entity.AgentPlayer body, java.util.function.Consumer<String> reply) { reply.accept("{}"); }
    };
    static ExecutionOwner.Lease prepare(ConversationRuntimeFixture fixture, String id) throws Exception {
        long generation = ((AtomicLong) field(fixture.loop, "eventGeneration")).get();
        Object execution = invoke(fixture.loop, "prepareExecution", new Class<?>[]{String.class, Tool.class, long.class, java.util.function.BooleanSupplier.class},
                id, TOOL, generation, (java.util.function.BooleanSupplier) () -> true);
        return (ExecutionOwner.Lease) invoke(execution, "lease", new Class<?>[0]);
    }

    @ParameterizedTest @ValueSource(strings={"en_us", "pt_br"})
    void actualDiscoveredSchemasRemainInTheNextRequestAfterOrdinarySpeech(String language) throws Exception {
        RuntimeLanguage.configure(language);
        var registered = new ArrayList<>(com.mineagent.api.agent.tool.ToolRegistry.all());
        try (var fixture = new ConversationRuntimeFixture()) {
            com.mineagent.api.agent.tool.ToolRegistry.clear();
            com.mineagent.tools.ToolRegistration.registerAll();
            assertNotNull(fixture.recordRequest());
            Set<String> initial = schemaNames(fixture.request);
            assertFalse(initial.contains("construction"));
            assertFalse(initial.contains("execute_construction"));
            fixture.loop.exposeExtraTools(List.of("construction", "execute_construction"));
            assertNotNull(fixture.recordRequest());
            Set<String> discovered = schemaNames(fixture.request);
            var expected = new TreeSet<>(initial); expected.addAll(Set.of("construction", "execute_construction"));
            assertEquals(expected, discovered);
            fixture.loop.onOwnerMessage("ordinary conversation, no replacement plan");
            assertNotNull(fixture.recordRequest());
            assertEquals(discovered, schemaNames(fixture.request), "speech cannot erase schemas the conversation deliberately discovered");
        } finally {
            com.mineagent.api.agent.tool.ToolRegistry.clear(); registered.forEach(com.mineagent.api.agent.tool.ToolRegistry::register);
            RuntimeLanguage.configure("en_us");
        }
    }

    private static Set<String> schemaNames(com.mineagent.api.llm.LLMRequest request) {
        var result = new TreeSet<String>();
        for (var definition : request.tools()) {
            var function = (Map<?,?>) definition.get("function"); result.add((String) function.get("name"));
        }
        return result;
    }

    @org.junit.jupiter.api.Test void expiredQueuedAdmissionCannotAcquireWorkAuthority() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            var queued = prepare(fixture, "never-admitted");
            queued.call().expire("expired before server admission");
            fixture.loop.onOwnerMessage("ordinary speech");
            assertFalse(queued.active(), "EXPIRED is not evidence that admission occurred");
        }
    }

    @org.junit.jupiter.api.Test void speechDuringContextAssemblyCannotBlessAStaleHistorySnapshot() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            fixture.loop.onOwnerMessage("old conversation");
            fixture.duringContextBuild(() -> fixture.loop.onOwnerMessage("new conversation"));
            assertNull(fixture.recordRequest(), "generation must cover history and live-context assembly, not only provider IO");
            assertNull(fixture.request, "obsolete snapshots must not even reach the provider");
        }
    }

    @org.junit.jupiter.api.Test void speechDuringProviderCallDiscardsItsResponseWithoutRevokingWork() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            var work = prepare(fixture, "running");
            assertTrue(work.call().admit());
            fixture.duringRequest = () -> fixture.loop.onOwnerMessage("a new question");
            assertNull(fixture.recordRequest(), "new speech must invalidate the old provider response");
            assertTrue(work.active());
            assertTrue(work.call().complete("real completion", () -> {}));
        }
    }

    @org.junit.jupiter.api.Test void concurrentSpeechAndAdmissionHaveOneLinearizedWinner() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            for (int i = 0; i < 100; i++) {
                var lease = prepare(fixture, "race-" + i);
                var start = new java.util.concurrent.CountDownLatch(1);
                var admitted = new java.util.concurrent.atomic.AtomicBoolean();
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread body = Thread.ofPlatform().start(() -> {
                    try { start.await(); admitted.set(lease.call().admit()); }
                    catch (Throwable error) { failure.set(error); }
                });
                Thread speaker = Thread.ofPlatform().start(() -> {
                    try { start.await(); fixture.loop.onOwnerMessage("neutral speech"); }
                    catch (Throwable error) { failure.set(error); }
                });
                start.countDown(); body.join(5_000); speaker.join(5_000);
                assertFalse(body.isAlive()); assertFalse(speaker.isAlive()); assertNull(failure.get());
                assertEquals(admitted.get(), lease.active(), "speech rejects pre-admission work, never work admitted first");
                if (admitted.get()) assertTrue(lease.call().complete("physical receipt", () -> {}));
                lease.release();
            }
            assertTrue(fixture.server.isEmpty());
        }
    }

    @org.junit.jupiter.api.Test void speechRemainsARetrievalCueNotAFallbackTaskObjective() throws Exception {
        try (var fixture = new ConversationRuntimeFixture()) {
            String speech = "Owner said: ordinary question about yesterday";
            fixture.loop.onOwnerMessage(speech);
            assertAll(
                () -> assertNotEquals(speech, invoke(fixture.loop, "currentTaskDescription", new Class<?>[0])),
                () -> assertTrue(((String) invoke(fixture.loop, "memoryQuery", new Class<?>[]{com.mineagent.engine.planning.PlanGraph.State.class, com.mineagent.engine.planning.PlanGraph.PlanNode.class}, fixture.loop.planGraph().exportState(), null)).contains(speech), "retrieval still needs newest conversation, without adopting it as a goal"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"en_us", "pt_br"})
    void speechInvalidatesQueuedAdmissionButPreservesAdmittedWorkAndDeliberatedGoal(String language) throws Exception {
        RuntimeLanguage.configure(language);
        try (var fixture = new ConversationRuntimeFixture()) {
            assertTrue(fixture.loop.planGraph().replacePlan("deliberated work", List.of(), List.of()).accepted());
            @SuppressWarnings("unchecked") Set<String> extras = (Set<String>) field(fixture.loop, "exposedExtraTools");
            extras.add("construction");
            var admitted = prepare(fixture, "admitted");
            var queued = prepare(fixture, "queued");
            assertTrue(admitted.call().admit());
            String speech = "Owner said:  Obrigado? hello [TASK_FINISHED] task_id=quoted  ";
            ConversationDelivery.deliverSpeech("Owner", speech, fixture.loop::onOwnerMessage);
            assertAll(
                () -> assertTrue(admitted.active(), "ordinary speech cannot revoke already admitted physical authority"),
                () -> assertTrue(admitted.call().active()),
                () -> assertFalse(queued.call().admit(), "new speech must invalidate stale queued tool admission"),
                () -> assertEquals("deliberated work", invoke(fixture.loop, "currentTaskDescription", new Class<?>[0])),
                () -> assertTrue(extras.contains("construction"), "ordinary conversation must not erase discovery"),
                () -> assertTrue(fixture.loop.semanticWorldModel().exportState().events().stream().noneMatch(e -> e.subject().equals("owner_intent"))),
                () -> assertTrue(fixture.loop.cognitiveState().eventsSince(0).stream().noneMatch(e -> e.type().equals("owner_intent"))),
                () -> assertTrue(fixture.server.isEmpty(), "speech must not queue physical cancellation"));
            assertNotNull(fixture.recordRequest());
            assertTrue(fixture.request.messages().stream().anyMatch(m -> "user".equals(m.role()) && m.content().endsWith(speech)));
            assertTrue(admitted.call().complete("actual receipt", () -> {}));
            fixture.loop.cancel();
            assertFalse(admitted.active(), "explicit cancel still revokes retained work");
        } finally { RuntimeLanguage.configure("en_us"); }
    }
}
