package com.mineagent.engine.identity;

import com.mineagent.api.task.TaskState;
import com.mineagent.engine.cognition.TacticalDecision;
import com.mineagent.engine.skill.SkillRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class IdentityIdleArbitrationTest {
    @Test void submissionRejectionAfterAvailabilityCheckClearsTicketAndRemainsBounded() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("reject-race","literal"); f.executor.reject=true;
            f.tick(101); f.run();
            assertFalse(f.loop.isInProgress()); assertTrue(f.requests.isEmpty());
            assertNull(IdentityIdleFixture.field(f.loop,"idleIdentityGoal"));
            for(int i=0;i<40;i++) f.tick(102+i);
            assertEquals(1,f.executor.submissions,"rejection must not storm");
        }
    }
    @ParameterizedTest @ValueSource(strings={"RUNNING","WAITING_TASK","VERIFYING","PAUSED_TASK","PAUSED_LOOP"})
    void activeAndLifecyclePausedSkillCannotBeReplacedByIdleGoal(String status) throws Exception {
        var tools=new ArrayList<>(com.mineagent.api.agent.tool.ToolRegistry.all());
        try(var f=new IdentityIdleFixture("en_us")) {
            com.mineagent.api.agent.tool.ToolRegistry.register(new com.mineagent.api.agent.tool.Tool() {
                public String name() { return "craft"; }
                public String description() { return "fixture"; }
                public Map<String,Object> parameterSchema() { return Map.of(); }
                @Override public boolean dispatchesAsyncTask() { return true; }
                public void onServerCall(String id,com.google.gson.JsonObject args,com.mineagent.api.entity.AgentPlayer body,java.util.function.Consumer<String> reply) { reply.accept("{\"async\":true}"); }
            });
            f.immediateServer=true;
            f.goal("skill-blocker","literal");
            f.loop.skillLibrary().registerSequence("skill","fixture","fixture","[{\"tool\":\"craft\",\"args\":{},\"expected_effects\":[{\"subject\":\"unobserved\",\"predicate\":\"unverified\"}]}]",true);
            assertTrue(f.loop.startSkill("skill",null).accepted());
            if(status.equals("PAUSED_LOOP")) f.loop.pause();
            else {
                IdentityIdleFixture.set(f.loop.skillRuntime(),"status",status.equals("PAUSED_TASK") ? SkillRuntime.Status.WAITING_TASK : SkillRuntime.Status.valueOf(status));
                if(status.equals("PAUSED_TASK")) IdentityIdleFixture.set(f.loop.skillRuntime(),"taskPaused",true);
                if(status.equals("VERIFYING")) IdentityIdleFixture.set(f.loop.skillRuntime(),"verificationDeadlineTick",Long.MAX_VALUE);
            }
            f.tick(101); f.run(); assertTrue(f.requests.isEmpty());
            if(!status.equals("PAUSED_LOOP")) assertTrue(f.loop.skillRuntime().active());
        } finally { com.mineagent.api.agent.tool.ToolRegistry.clear(); tools.forEach(com.mineagent.api.agent.tool.ToolRegistry::register); }
    }
    @ParameterizedTest @ValueSource(strings={"owner","inbox","running-turn","tactical"})
    void existingDecisionEventsRetainPriorityOverGoalWake(String event) throws Exception {
        try(var f=new IdentityIdleFixture("pt_br")) {
            f.goal("later-goal","literal");
            switch(event) {
                case "owner" -> f.loop.onOwnerMessage("literal owner conversation");
                case "inbox" -> IdentityIdleFixture.invoke(f.loop,"enqueueInbox",new Class<?>[]{String.class},"literal existing machine event");
                case "running-turn" -> IdentityIdleFixture.set(f.loop,"inProgress",true);
                case "tactical" -> {
                    // The real bounded-cadence tick reads its previous decision; no idle admission may override local control.
                    @SuppressWarnings("unchecked") var decision=(AtomicReference<TacticalDecision>) IdentityIdleFixture.field(f.loop.realtimeCognition(),"latestDecision");
                    decision.set(new TacticalDecision(TacticalDecision.Posture.SURVIVE_NOW,.9,"fixture urgency",List.of(),true,false,100));
                }
            }
            f.tick(101); f.run();
            assertNull(IdentityIdleFixture.field(f.loop,"idleIdentityGoal"));
            if(event.equals("owner")) assertEquals(1,f.requests.size()); else assertTrue(f.requests.isEmpty());
            if(event.equals("running-turn")) IdentityIdleFixture.set(f.loop,"inProgress",false);
            if(event.equals("inbox")) { f.loop.wake("fixture-event"); f.run(); }
            if(!event.equals("tactical")) { f.tick(102); f.run(); assertTrue(f.sent().contains("later-goal")); }
        }
    }
    @Test void terminalSkillReleasesIdleExclusionEvenThoughItsReleasedLeaseObjectStillExists() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("after-skill","literal goal");
            f.loop.skillLibrary().registerSequence("completed-skill","fixture","fixture","[{\"tool\":\"craft\",\"args\":{}}]",true);
            assertTrue(f.loop.startSkill("completed-skill",null).accepted());
            f.loop.skillRuntime().cancel("fixture terminal cancellation",101);
            f.run(); assertEquals(SkillRuntime.Status.CANCELLED,f.loop.skillRuntime().snapshot().status());
            // The existing rolling-replan turn keeps priority after a skill terminal.
            f.tick(102); f.run();
            int ordinary=f.requests.size();
            f.tick(103); f.run(); assertEquals(ordinary+1,f.requests.size());
            assertEquals("after-skill",IdentityIdleRevisionTest.idleData(f).get("subject").getAsString());
        }
    }

    @Test void idleInspectionDoesNotHoldLoopMonitorWhileWaitingForSkillMonitor() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("loop-lock-order","literal");
            var skillLocked=new CountDownLatch(1); var releaseSkill=new CountDownLatch(1);
            var wakeResponsive=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
            Thread skill=new Thread(() -> { synchronized(f.loop.skillRuntime()) { skillLocked.countDown(); try { releaseSkill.await(3,TimeUnit.SECONDS); } catch(InterruptedException error) { Thread.currentThread().interrupt(); } } });
            Thread idle=new Thread(() -> { try { IdentityIdleFixture.invoke(f.loop,"wakeIdleIdentityGoal",new Class<?>[]{long.class},101L); } catch(Throwable error) { failure.set(error); } });
            Thread wake=new Thread(() -> { try { f.loop.wake("fixture ordinary event"); wakeResponsive.countDown(); } catch(Throwable error) { failure.set(error); } });
            boolean responsive;
            try {
                skill.start(); assertTrue(skillLocked.await(2,TimeUnit.SECONDS)); idle.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(idle.getState()!=Thread.State.BLOCKED && idle.isAlive() && System.nanoTime()<deadline) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED,idle.getState(),"idle should wait for the real skill monitor");
                wake.start(); responsive=wakeResponsive.await(300,TimeUnit.MILLISECONDS);
            } finally { releaseSkill.countDown(); skill.join(2000); idle.join(2000); if(wake.getState()!=Thread.State.NEW) wake.join(2000); }
            assertFalse(skill.isAlive()); assertFalse(idle.isAlive()); assertFalse(wake.isAlive()); assertNull(failure.get());
            assertTrue(responsive,"idle inspection must leave the loop monitor available to skill completion wakes");
        }
    }

    @Test void idleInspectionDoesNotHoldAdmissionGateWhileWaitingForSkillMonitor() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("lock-order","literal");
            var skillLocked=new CountDownLatch(1); var releaseSkill=new CountDownLatch(1);
            var gateResponsive=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
            Thread skill=new Thread(() -> { synchronized(f.loop.skillRuntime()) { skillLocked.countDown(); try { releaseSkill.await(3,TimeUnit.SECONDS); } catch(InterruptedException error) { Thread.currentThread().interrupt(); } } });
            Thread idle=new Thread(() -> { try { IdentityIdleFixture.invoke(f.loop,"wakeIdleIdentityGoal",new Class<?>[]{long.class},101L); } catch(Throwable error) { failure.set(error); } });
            var owner=(com.mineagent.engine.execution.ExecutionOwner) IdentityIdleFixture.field(f.loop,"executionOwner");
            Thread probe=new Thread(() -> owner.invalidate(gateResponsive::countDown));
            boolean responsive;
            try {
                skill.start(); assertTrue(skillLocked.await(2,TimeUnit.SECONDS)); idle.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(idle.getState()!=Thread.State.BLOCKED && idle.isAlive() && System.nanoTime()<deadline) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED,idle.getState(),"idle should inspect the owned skill monitor");
                probe.start(); responsive=gateResponsive.await(300,TimeUnit.MILLISECONDS);
            } finally { releaseSkill.countDown(); skill.join(2000); idle.join(2000); if(probe.getState()!=Thread.State.NEW) probe.join(2000); }
            assertFalse(skill.isAlive()); assertFalse(idle.isAlive()); assertFalse(probe.isAlive()); assertNull(failure.get());
            assertTrue(responsive,"idle inspection must not hold the admission gate across the skill monitor");
        }
    }

    @Test void actualConcurrentAdmissionAndIdleTicksPreserveAdmittedAuthority() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("race","literal");
            for(int i=0;i<100;i++) {
                var lease=IdentityIdleRuntimeTest.prepare(f);
                var barrier=new CyclicBarrier(2);
                var admitted=new AtomicBoolean();
                var failure=new AtomicReference<Throwable>();
                Thread contender=new Thread(() -> { try { barrier.await(2,TimeUnit.SECONDS); admitted.set(lease.call().admit()); } catch(Throwable error) { failure.set(error); } });
                contender.start(); barrier.await(2,TimeUnit.SECONDS); f.tick(101+i); contender.join(2000);
                assertFalse(contender.isAlive(),"admission race must terminate"); assertNull(failure.get());
                f.run(); assertTrue(admitted.get()); assertTrue(lease.active(),"idle polling must not revoke admitted work");
                assertTrue(f.requests.isEmpty());
                lease.call().expire("fixture release"); lease.release();
            }
            f.tick(301); f.run(); assertEquals(1,f.requests.size(),"blocked polling must not consume the goal");
        }
    }
}
