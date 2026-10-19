package com.mineagent.engine.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityReflectionTest {
    @Test void reflectionOffersLocalizedBoundedProseAndClosesImmediately() throws Exception {
        var identity = new EvolvingIdentity();
        var reflection = new IdentityReflection(identity);
        var context = assertDoesNotThrow(() -> IdentityReflection.class.getMethod("context", String.class, int.class));
        assertEquals("", context.invoke(reflection, "en_us", 600));
        identity.recordEvidence("player_claim", "owner", "The orchard is calmer", 1);
        reflection.open(1, 1, false);
        String english = (String) context.invoke(reflection, "en_us", 600);
        String portuguese = (String) context.invoke(reflection, "pt_br", 600);
        assertTrue(english.contains("reflection"));
        assertTrue(portuguese.contains("reflexão"));
        assertFalse(english.contains("["));
        assertTrue(english.length() <= 600);
        reflection.close();
        assertEquals("", context.invoke(reflection, "pt_br", 600));
    }

    @Test void cancelledOrStaleReflectionCannotMutateIdentityAndUpdatesAreBounded() throws Exception {
        var identity = new EvolvingIdentity();
        var evidence = identity.recordEvidence("observation", "executor", "Food collected", 1);
        var reflection = new IdentityReflection(identity);
        reflection.open(1, 5, false);
        var revise = assertDoesNotThrow(() -> IdentityReflection.class.getMethod("revise", long.class,
                String.class, String.class, String.class, String.class, java.util.List.class,
                double.class, long.class, String.class));
        Object[] stale = {4L, "goal", "food", "Gather food", "Hunger", java.util.List.of(evidence.id()), .7, 0L, ""};
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> revise.invoke(reflection, stale));
        assertTrue(identity.entries().isEmpty());
        for (String subject : java.util.List.of("food", "reserve")) {
            revise.invoke(reflection, 5L, "goal", subject, "Keep supplies", "Hunger", java.util.List.of(evidence.id()), .7, 0L, "");
        }
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> revise.invoke(reflection,
                5L, "goal", "excess", "Keep supplies", "Hunger", java.util.List.of(evidence.id()), .7, 0L, ""));
        identity.recordEvidence("observation", "executor", "A later experience", 1300);
        reflection.open(1300, 6, false);
        IdentityReflection.class.getMethod("close").invoke(reflection);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> revise.invoke(reflection,
                6L, "goal", "cancelled", "Keep supplies", "Hunger", java.util.List.of(evidence.id()), .7, 0L, ""));
        assertEquals(2, identity.entries().size());
    }

    @Test void reflectionNeedsNewEvidenceAndRespectsCadenceWithoutBackgroundRequests() throws Exception {
        var type = assertDoesNotThrow(() -> Class.forName("com.mineagent.engine.identity.IdentityReflection"));
        var identity = new EvolvingIdentity();
        var reflection = type.getConstructor(EvolvingIdentity.class).newInstance(identity);
        var open = type.getMethod("open", long.class, long.class, boolean.class);
        assertEquals(false, open.invoke(reflection, 100L, 1L, false));
        identity.recordEvidence("player_claim", "owner", "The orchard is safer", 100);
        assertEquals(false, open.invoke(reflection, 100L, 1L, true), "Do not compete with the occupied body");
        assertEquals(true, open.invoke(reflection, 100L, 1L, false));
        for (long tick = 101; tick < 1300; tick++) {
            assertEquals(false, open.invoke(reflection, tick, 1L, false));
        }
        assertEquals(false, open.invoke(reflection, 1300L, 2L, false));
        identity.recordEvidence("observation", "executor", "Fruit collected", 1300);
        assertEquals(true, open.invoke(reflection, 1300L, 2L, false));
    }
}
