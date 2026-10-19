package com.mineagent.engine.identity;

import com.mineagent.api.task.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class IdentityIdlePolicyTest {
    @ParameterizedTest @ValueSource(strings={"en_us","pt_br"})
    void cooldownDedupAndCitedRevisionReachOnlyBoundedRealRequests(String language) throws Exception {
        try(var f=new IdentityIdleFixture(language)) {
            f.goal("goal", "initial goal"); f.tick(101); f.run();
            for(long tick=102; tick<1400; tick++) { f.tick(tick); f.run(); }
            assertEquals(1,f.requests.size());
            var unrelated=f.loop.identity().recordEvidence("observation","other","unrelated",1400);
            f.tick(1400); f.run(); assertEquals(1,f.requests.size());
            var replay=f.loop.identity().recordEvidence("observation","fixture","evidence for goal",1401);
            f.loop.identity().revise("goal","goal","replayed IDs are not novelty","reason",List.of(replay.id()),.7,1,"reconsidered");
            f.tick(1401); f.run(); assertEquals(1,f.requests.size());
            var fresh=f.loop.identity().recordEvidence("observation","fixture","genuinely different literal evidence",1402);
            f.loop.identity().revise("goal","goal","new revision literal","new reason",List.of(fresh.id()),.7,2,"reconsidered again");
            f.tick(1402); f.run(); assertEquals(2,f.requests.size());
            assertTrue(f.sent().contains("new revision literal")); assertTrue(f.sent().contains("r3"));
            assertTrue(f.sent().contains(fresh.id()));
            f.tick(1200); f.run(); f.tick(2602); f.run(); assertEquals(2,f.requests.size());
        }
    }
    @ParameterizedTest @ValueSource(strings={"RUNNING","PAUSED"})
    void bodyOwnershipDoesNotConsumeOpportunityEvenWhenPaused(String state) throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("after body", "literal");
            f.loop.onTaskProgress("task",TaskState.valueOf(state),null,"fixture",100);
            f.tick(101); f.run(); assertTrue(f.requests.isEmpty());
            f.loop.onTaskFinished("task","fixture",null,TaskState.CANCELLED,null,"fixture terminal",102);
            f.tick(103); f.run(); assertEquals(1,f.requests.size());
        }
    }
    @Test void noGoalOrOnlySpeechPreferenceAndWorldEvidenceCannotWake() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.loop.onOwnerMessage("I would like an observation, not an automatically accepted goal"); f.run();
            var evidence=f.loop.identity().recordEvidence("observation","fixture","literal",10);
            f.loop.identity().revise("preference","preference","literal","reason",List.of(evidence.id()),.7,0,"");
            f.loop.identity().revise("world","world","literal","reason",List.of(evidence.id()),.7,0,"");
            int before=f.requests.size();
            f.tick(101); f.run(); f.tick(1500); f.run();
            assertEquals(before,f.requests.size());
            assertTrue(f.loop.identity().entries().stream().noneMatch(e -> e.kind().equals("goal")));
        }
    }
    @Test void restoredGoalsRotateWithoutStormAndExactTicketSurvivesCompactRetrieval() throws Exception {
        try(var f=new IdentityIdleFixture("pt_br")) {
            for(int i=0;i<5;i++) f.goal("subject-"+i, "literal statement-"+i);
            var state=f.loop.identity().exportState();
            f.loop.identity().importState(state);
            for(int i=0;i<5;i++) {
                long tick=101+i*1200L;
                f.tick(tick); f.run(); assertEquals(i+1,f.requests.size());
                String live=f.requests.getLast().messages().getLast().content();
                assertTrue(live.contains("subject-"+i)); assertTrue(live.contains("literal statement-"+i));
                for(long poll=tick+1;poll<tick+5;poll++) { f.tick(poll); f.run(); }
                assertEquals(i+1,f.requests.size());
            }
            f.loop.identity().importState(state); f.tick(10000); f.run(); assertEquals(5,f.requests.size());
        }
    }
    @Test void rejectedExecutorDoesNotLeaveStaleTicketOrRetryStorm() throws Exception {
        try(var f=new IdentityIdleFixture("en_us")) {
            f.goal("rejected", "literal"); f.executor.shutdown();
            for(int i=0;i<30;i++) f.tick(101+i);
            assertTrue(f.requests.isEmpty()); assertFalse(f.loop.isInProgress());
            assertNull(IdentityIdleFixture.field(f.loop,"idleIdentityGoal"));
        }
    }
}
