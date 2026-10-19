package com.mineagent.engine.identity;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityMemoryAccessTest {
    @Test void oldCitedEvidenceRemainsIndividuallyRetrievableWithoutDumpingIdentity() {
        var identity = new EvolvingIdentity();
        var old = identity.recordEvidence("observation", "executor", "Unexpected detail from the first trip", 1);
        identity.revise("world", "distant orchard", "Route uncertain", "First trip detail", java.util.List.of(old.id()), .7, 0, "");
        for (int i = 0; i < 30; i++) identity.recordEvidence("observation", "executor", "Other outcome " + i, i + 2);
        var access = new IdentityMemoryAccess(identity, new IdentityReflection(identity), () -> 1);
        var args = new JsonObject(); args.addProperty("operation", "recall");
        var ids = new com.google.gson.JsonArray(); ids.add(old.id()); args.add("evidence_ids", ids);
        var result = com.google.gson.JsonParser.parseString(access.execute(args, "en_us")).getAsJsonObject();
        assertEquals(1, result.getAsJsonArray("evidence").size());
        assertEquals(old.id(), result.getAsJsonArray("evidence").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test void nativeMutationUsesRealEvidenceAndCurrentReflectionGeneration() {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("player_claim", "owner", "The orchard avoids the cliff", 1);
        var reflection = new IdentityReflection(identity);
        var generation = new java.util.concurrent.atomic.AtomicLong(1);
        var access = new IdentityMemoryAccess(identity, reflection, generation::get);
        var args = new JsonObject();
        args.addProperty("operation", "revise"); args.addProperty("kind", "preference");
        args.addProperty("subject", "orchard"); args.addProperty("statement", "Prefer the orchard route");
        args.addProperty("reason", "The owner's report gives a reason to try the safer route");
        var ids = new com.google.gson.JsonArray(); ids.add(evidence.id()); args.add("evidence_ids", ids);
        args.addProperty("confidence", .9); args.addProperty("expected_revision", 0);
        assertTrue(access.execute(args, "en_us").contains("reflection_closed_or_stale"),
                "The model must know that repeating a rejected reflection cannot help");
        reflection.open(1, generation.get(), false);
        assertTrue(access.execute(args, "en_us").contains("updated"));
        assertEquals(.55, identity.entries().getFirst().confidence());
        generation.incrementAndGet();
        args.addProperty("subject", "stale");
        assertTrue(access.execute(args, "pt_br").contains("error"));
        assertEquals(1, identity.entries().size());
    }

    @Test void memoryRecallIsProgressiveAndCannotFabricateObservations() throws Exception {
        var identity = new EvolvingIdentity();
        identity.recordEvidence("player_claim", "owner", "Orchard fruit is safe", 1);
        for (int i = 0; i < 40; i++) identity.recordEvidence("observation", "executor", "Actual outcome " + i, i + 1);
        var type = assertDoesNotThrow(() -> Class.forName("com.mineagent.engine.identity.IdentityMemoryAccess"));
        var reflection = new IdentityReflection(identity);
        var access = type.getConstructor(EvolvingIdentity.class, IdentityReflection.class, java.util.function.LongSupplier.class)
                .newInstance(identity, reflection, (java.util.function.LongSupplier) () -> 1);
        var execute = type.getMethod("execute", JsonObject.class, String.class);
        var recall = new JsonObject();
        recall.addProperty("operation", "recall");
        recall.addProperty("query", "orchard");
        String text = (String) execute.invoke(access, recall, "pt_br");
        assertTrue(text.contains("Orchard fruit is safe"));
        assertTrue(text.contains("relato do jogador"));
        assertFalse(text.contains("Actual outcome"));
        assertTrue(text.length() <= 6000);
        var fabricate = new JsonObject(); fabricate.addProperty("operation", "observe");
        execute.invoke(access, fabricate, "en_us");
        assertEquals(41, identity.evidenceSequence());
    }
}
