package com.mineagent.engine.identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

final class IdentityIdleRuntimeTest {
    @ParameterizedTest @ValueSource(strings={"en_us","pt_br"})
    void idleTickReachesRealRequestWithExactGoalWithoutChoosingAPlan(String language) throws Exception {
        try (var f = new IdentityIdleFixture(language)) {
            var goal = f.goal("literal {subject} \\ opaque", "Preserve literal goal text, not an action recipe");
            f.tick(101);
            f.run();
            assertEquals(1, f.requests.size(), "an eligible idle goal must wake the real request consumer");
            var selected=IdentityIdleRevisionTest.idleData(f);
            assertEquals(goal.subject(),selected.get("subject").getAsString());
            assertEquals(goal.statement(),selected.get("statement").getAsString());
            assertEquals(goal.reason(),selected.get("reason").getAsString());
            assertEquals(goal.revision(),selected.get("revision").getAsLong());
            assertEquals(goal.evidenceIds().getFirst(),selected.getAsJsonArray("evidence_ids").get(0).getAsString());
            assertTrue(f.sent().contains(language.equals("en_us") ? "Personal goal available for deliberation" : "Objetivo pessoal disponível para deliberação"));
            assertTrue(f.loop.planner().exportState().goal().isEmpty());
            assertTrue(f.server.isEmpty(), "identity wake must not force action or speech");
        }
    }
    @ParameterizedTest @ValueSource(strings={"cancel","pause","death","revision","body-task","skill"})
    void queuedIdleTurnMustRecheckLifecycleAndOwnershipBeforeRequest(String change) throws Exception {
        try (var f = new IdentityIdleFixture("en_us")) {
            f.goal("queued", "original goal");
            f.tick(101);
            assertEquals(1, f.executor.queue.size());
            switch (change) {
                case "cancel" -> f.loop.cancel();
                case "pause" -> f.loop.pause();
                case "death" -> f.body.alive=false;
                case "revision" -> {
                    var id=f.loop.identity().recordEvidence("observation", "fixture", "new evidence", 102).id();
                    f.loop.identity().revise("goal", "queued", "revised goal", "changed reason", java.util.List.of(id), .7, 1, "reconsidered");
                }
                case "body-task" -> f.loop.onTaskProgress("paused", com.mineagent.api.task.TaskState.PAUSED, null, "literal", 102);
                case "skill" -> {
                    f.loop.skillLibrary().registerSequence("queued-skill", "fixture", "fixture", "[{\"tool\":\"craft\",\"args\":{}}]", true);
                    assertTrue(f.loop.startSkill("queued-skill", null).accepted());
                }
            }
            f.run();
            assertTrue(f.requests.isEmpty(), "queued identity work is invalid after " + change);
        }
    }

    @ParameterizedTest @ValueSource(strings={"cancel","pause","death","body-task","revision","admitted"})
    void contextAssemblyRaceCannotSendIdleRequest(String change) throws Exception {
        try(var f=new IdentityIdleFixture("pt_br")) {
            f.goal("race", "original goal"); f.tick(101);
            f.body.contextCallback=() -> {
                switch(change) {
                    case "cancel" -> f.loop.cancel();
                    case "pause" -> f.loop.pause();
                    case "death" -> f.body.alive=false;
                    case "body-task" -> f.loop.onTaskProgress("new-task", com.mineagent.api.task.TaskState.PAUSED, null, "literal", 102);
                    case "revision" -> {
                        var id=f.loop.identity().recordEvidence("observation", "fixture", "new evidence", 102).id();
                        f.loop.identity().revise("goal", "race", "revised", "reason", java.util.List.of(id), .7, 1, "changed");
                    }
                    case "admitted" -> { try { assertTrue(prepare(f).call().admit()); } catch(Exception e) { throw new AssertionError(e); } }
                }
            };
            f.run(); assertTrue(f.requests.isEmpty(), "context changed during assembly: " + change);
        }
    }
    @ParameterizedTest @ValueSource(strings={"cancel","pause","death","revision","admitted"})
    void inflightIdleResponseLosesAuthorityAfterChangedAssumptions(String change) throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("flight", "original goal"); f.tick(101);
            f.response=() -> com.mineagent.api.llm.ChatMessage.assistant("stale fixture speech");
            f.duringRequest=() -> {
                switch(change) {
                    case "cancel" -> f.loop.cancel();
                    case "pause" -> f.loop.pause();
                    case "death" -> f.body.alive=false;
                    case "revision" -> {
                        var id=f.loop.identity().recordEvidence("observation", "fixture", "new evidence", 102).id();
                        f.loop.identity().revise("goal", "flight", "revised", "reason", java.util.List.of(id), .7, 1, "changed");
                    }
                    case "admitted" -> { try { assertTrue(prepare(f).call().admit()); } catch(Exception e) { throw new AssertionError(e); } }
                }
            };
            f.run(); assertEquals(1,f.requests.size());
            @SuppressWarnings("unchecked") var history=(java.util.List<com.mineagent.api.llm.ChatMessage>) IdentityIdleFixture.field(f.loop,"history");
            assertTrue(history.stream().noneMatch(m -> "stale fixture speech".equals(m.content())), "stale response must not publish: " + change);
        }
    }
    @ParameterizedTest @ValueSource(strings={"pending","running","dead"})
    void idleAdmissionExcludesExecutionOwnershipAndDeadBodies(String state) throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("blocked", "literal goal");
            if(state.equals("dead")) f.body.alive=false;
            else { var lease=prepare(f); if(state.equals("running")) assertTrue(lease.call().admit()); }
            f.tick(101); f.run();
            assertTrue(f.requests.isEmpty(), "idle must not compete with " + state);
        }
    }
    @org.junit.jupiter.api.Test
    void completedAsyncAdmissionStillOwnsWorkUntilItsRealTerminalReceipt() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("after-async","literal");
            var lease=prepare(f); assertTrue(lease.call().admit());
            assertTrue(lease.call().complete("{\"async\":true}",() -> {}));
            f.tick(101); f.run(); assertTrue(f.requests.isEmpty(),"completed admission payload is not a task terminal");
            f.loop.onTaskFinished("native-fixture","fixture",null,com.mineagent.api.task.TaskState.CANCELLED,null,"fixture terminal",102);
            f.run(); int ordinary=f.requests.size();
            f.tick(103); f.run(); assertEquals(ordinary+1,f.requests.size());
            assertEquals("after-async",IdentityIdleRevisionTest.idleData(f).get("subject").getAsString());
        }
    }

    static com.mineagent.engine.execution.ExecutionOwner.Lease prepare(IdentityIdleFixture f) throws Exception {
        var tool=new com.mineagent.api.agent.tool.Tool() {
            public String name() { return "fixture"; }
            public String description() { return "fixture"; }
            public java.util.Map<String,Object> parameterSchema() { return java.util.Map.of(); }
            public void onServerCall(String id, com.google.gson.JsonObject args, com.mineagent.api.entity.AgentPlayer body, java.util.function.Consumer<String> reply) { }
        };
        long generation=((java.util.concurrent.atomic.AtomicLong) IdentityIdleFixture.field(f.loop,"eventGeneration")).get();
        var execution=IdentityIdleFixture.invoke(f.loop,"prepareExecution",new Class<?>[]{String.class,com.mineagent.api.agent.tool.Tool.class,long.class,java.util.function.BooleanSupplier.class},
                "native-fixture",tool,generation,(java.util.function.BooleanSupplier) () -> true);
        return (com.mineagent.engine.execution.ExecutionOwner.Lease) IdentityIdleFixture.invoke(execution,"lease",new Class<?>[0]);
    }

    @ParameterizedTest @ValueSource(strings={"en_us","pt_br"})
    void ordinaryTurnAfterIdleDoesNotRetainItsTransientTicket(String language) throws Exception {
        try (var f=new IdentityIdleFixture(language)) {
            f.goal("ticket", "literal goal");
            f.tick(101); f.run();
            f.loop.onOwnerMessage("ordinary conversation"); f.run();
            assertEquals(2, f.requests.size());
            assertEquals(f.requests.getFirst().messages().getFirst(),f.requests.getLast().messages().getFirst(),"stable prompt must not retain transient goal data");
            String live=f.requests.getLast().messages().getLast().content();
            assertFalse(live.contains(language.equals("en_us") ? "Personal goal available for deliberation" : "Objetivo pessoal disponível para deliberação"));
        }
    }
}
