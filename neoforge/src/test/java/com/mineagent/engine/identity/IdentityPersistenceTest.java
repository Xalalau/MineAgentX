package com.mineagent.engine.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IdentityPersistenceTest {
    @TempDir Path directory;

    @Test void concurrentSaveCannotCheckpointReflectionAheadOfItsEvidenceSnapshot() throws Exception {
        var identity = new EvolvingIdentity(); var reflection = new IdentityReflection(identity);
        identity.recordEvidence("observation", "executor", "Before save", 1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread saver = new Thread(() -> {
            try { IdentityPersistence.save(directory, identity, reflection); }
            catch (Throwable error) { failure.set(error); }
        });
        synchronized (reflection) {
            saver.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (saver.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(Thread.State.BLOCKED, saver.getState());
            identity.recordEvidence("observation", "executor", "During save", 2);
            assertTrue(reflection.open(2, 1, false));
        }
        saver.join(5000);
        assertFalse(saver.isAlive()); assertNull(failure.get());
        var restored = new EvolvingIdentity();
        assertDoesNotThrow(() -> IdentityPersistence.load(directory, restored, new IdentityReflection(restored)));
        assertEquals(2, restored.evidenceSequence());
    }

    @Test void diskRestorationCannotExposeMutableEvidenceOrRevisionLists() throws Exception {
        var identity = new EvolvingIdentity(); var reflection = new IdentityReflection(identity);
        var evidence = identity.recordEvidence("observation", "executor", "Fruit collected", 1);
        identity.revise("preference", "fruit", "Prefer fruit", "Reliable supply", java.util.List.of(evidence.id()), .7, 0, "");
        IdentityPersistence.save(directory, identity, reflection);
        var restored = new EvolvingIdentity();
        IdentityPersistence.load(directory, restored, new IdentityReflection(restored));
        assertThrows(UnsupportedOperationException.class, () -> restored.entries().getFirst().evidenceIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> restored.entries().getFirst().history().clear());
    }

    @Test void malformedOrFutureIdentityFileCannotReplaceValidInMemoryState() throws Exception {
        var identity = new EvolvingIdentity();
        var reflection = new IdentityReflection(identity);
        identity.recordEvidence("observation", "executor", "Original", 1);
        var before = identity.exportState();
        for (String bad : java.util.List.of("{\"version\":99}", "null", "{\"version\":1,\"identity\":{\"sequence\":5,\"evidence\":[],\"entries\":[{}]}}")) {
            java.nio.file.Files.writeString(directory.resolve("identity.json"), bad);
            assertThrows(java.io.IOException.class, () -> IdentityPersistence.load(directory, identity, reflection));
            assertEquals(before, identity.exportState());
        }
    }

    @Test void roundTripRetainsEvidenceReasonsRevisionsAndReflectionCheckpoint() throws Exception {
        var identity = new EvolvingIdentity();
        var reflection = new IdentityReflection(identity);
        var observed = identity.recordEvidence("observation", "executor", "Orchard supplied fruit", 10);
        identity.revise("preference", "orchard", "I favor the orchard", "Fruit was easy to obtain", java.util.List.of(observed.id()), .7, 0, "");
        var warning = identity.recordEvidence("player_claim", "owner", "The harvest belongs to someone", 20);
        identity.revise("preference", "orchard", "I avoid taking this fruit", "Permission is uncertain", java.util.List.of(warning.id()), .5, 1, "Easy food does not justify taking another player's harvest");
        reflection.open(20, 1, false);
        var codec = assertDoesNotThrow(() -> Class.forName("com.mineagent.engine.identity.IdentityPersistence"));
        codec.getMethod("save", Path.class, EvolvingIdentity.class, IdentityReflection.class)
                .invoke(null, directory, identity, reflection);
        assertTrue(java.nio.file.Files.isRegularFile(directory.resolve("identity.json")));
        var restored = new EvolvingIdentity();
        var resumed = new IdentityReflection(restored);
        codec.getMethod("load", Path.class, EvolvingIdentity.class, IdentityReflection.class)
                .invoke(null, directory, restored, resumed);
        assertEquals(identity.entries(), restored.entries());
        assertEquals(identity.evidence(), restored.evidence());
        restored.recordEvidence("observation", "executor", "Another real event", 21);
        assertFalse(resumed.open(21, 2, false), "Restart must not bypass the reflection cadence");
    }
}
