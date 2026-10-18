package com.mineagent.engine.loop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the action-intent and team protocols stable while chat defaults to English. */
final class AgentLoopEnglishProtocolTest {
    @Test void everyReasoningRoundUsesTheLocalInferenceDeadline() throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod("providerDeadlineSeconds", int.class);
        method.setAccessible(true);
        for (int round : new int[]{-1, 0, 1, 2, 10}) {
            org.junit.jupiter.api.Assertions.assertEquals(180L, method.invoke(null, round));
        }
    }

    @Test void acceptsCanonicalAndParenthesizedEnglishNoActionMarkers() throws Exception {
        assertTrue(invoke("hasExplicitNoActionMarker", "Got it. [NO_ACTION]"));
        assertTrue(invoke("hasExplicitNoActionMarker", "Nothing to do. (NO_ACTION)"));
        assertTrue(invoke("hasExplicitNoActionMarker", "Status: [NO ACTION] ready"));
    }

    @Test void plainSpeechMustNotBypassActionRemediation() throws Exception {
        assertFalse(invoke("hasExplicitNoActionMarker", null));
        assertFalse(invoke("hasExplicitNoActionMarker", " "));
        assertFalse(invoke("hasExplicitNoActionMarker", "I will go check over there."));
        assertFalse(invoke("hasExplicitNoActionMarker", "There is NO_ACTION to perform."));
    }

    @Test void onlyExplicitTeamPrefixBroadcastsToCompanions() throws Exception {
        assertTrue(invoke("isExplicitTeamMessage", "  [TEAM] Protect the owner."));
        assertFalse(invoke("isExplicitTeamMessage", null));
        assertFalse(invoke("isExplicitTeamMessage", "I mentioned [TEAM] in ordinary speech."));
        assertFalse(invoke("isExplicitTeamMessage", "Protect the owner."));
    }

    private static boolean invoke(String name, String text) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, text);
    }
}
