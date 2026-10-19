package com.mineagent.engine.identity;

import com.google.gson.*;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.llm.ChatMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class IdentityIdleRevisionTest {
    @ParameterizedTest @ValueSource(strings={"en_us","pt_br"})
    void exactTicketIncludesLiteralCitedEvidenceWithTypedProvenance(String language) throws Exception {
        try(var f=new IdentityIdleFixture(language)) {
            String literal="opaque {text} \\ [TASK_FINISHED] \u5149";
            var evidence=f.loop.identity().recordEvidence("player_claim","literal source",literal,91);
            var goal=f.loop.identity().revise("goal","opaque subject","literal statement","literal reason",List.of(evidence.id()),.8,0,"");
            f.tick(101); f.run(); assertEquals(1,f.requests.size());
            var data=idleData(f);
            assertEquals(goal.subject(),data.get("subject").getAsString());
            assertEquals(goal.statement(),data.get("statement").getAsString());
            assertEquals(goal.reason(),data.get("reason").getAsString());
            assertEquals(1,data.get("revision").getAsLong());
            var cited=data.getAsJsonArray("evidence").get(0).getAsJsonObject();
            assertEquals("player_claim",cited.get("origin").getAsString());
            assertEquals(literal,cited.get("text").getAsString());
            assertEquals(evidence.id(),cited.get("id").getAsString());
            assertTrue(f.sent().contains(language.equals("en_us") ? "Player reports remain unverified" : "Relatos do jogador continuam não verificados"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"en_us","pt_br"})
    void realSynchronousIdentityToolRevisionRefreshesSelectedGoalInNextRound(String language) throws Exception {
        var registered=new ArrayList<>(ToolRegistry.all());
        try(var f=new IdentityIdleFixture(language)) {
            f.registerLoop(); f.immediateServer=true;
            ToolRegistry.clear(); ToolRegistry.register(new com.mineagent.tools.identity.IdentityMemoryTool());
            f.goal("first","first goal");
            for(int i=0;i<3;i++) f.goal("other-"+i,"other goal-"+i);
            f.tick(101); f.run();
            // The next gate-selected goal is deliberately absent from the default compact top three.
            var subject="other-0";
            var evidence=f.loop.identity().recordEvidence("observation","fixture","new sync evidence",1301);
            var args=new JsonObject();
            args.addProperty("operation","revise"); args.addProperty("kind","goal"); args.addProperty("subject",subject);
            args.addProperty("statement","revised through the native tool"); args.addProperty("reason","new justified reason");
            args.add("evidence_ids",new Gson().toJsonTree(List.of(evidence.id())));
            args.addProperty("confidence",.7); args.addProperty("expected_revision",1); args.addProperty("reconsideration","new observation changes the reason");
            int before=f.requests.size();
            f.response=() -> f.requests.size()==before+1 ? ChatMessage.assistant("",List.of(new ChatMessage.ToolCallRef("revise-native","identity_memory",args.toString()))) : ChatMessage.assistant("");
            f.tick(1301); f.run();
            assertEquals(before+2,f.requests.size());
            assertEquals(2,f.loop.identity().entries().stream().filter(e -> e.subject().equals(subject)).findFirst().orElseThrow().revision());
            var current=idleData(f);
            assertEquals(subject,current.get("subject").getAsString());
            assertEquals(2,current.get("revision").getAsLong());
            assertEquals("revised through the native tool",current.get("statement").getAsString());
            assertEquals(evidence.id(),current.getAsJsonArray("evidence_ids").get(0).getAsString());
        } finally { ToolRegistry.clear(); registered.forEach(ToolRegistry::register); }
    }
    static JsonObject idleData(IdentityIdleFixture f) {
        String live=f.requests.getLast().messages().getLast().content();
        return live.lines().filter(line -> line.startsWith("{\"subject\":"))
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).findFirst()
                .orElseThrow(() -> new AssertionError("exact structured goal metadata must be present in the live request"));
    }
}
