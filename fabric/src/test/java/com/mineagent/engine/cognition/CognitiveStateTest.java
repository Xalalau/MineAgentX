package com.mineagent.engine.cognition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CognitiveStateTest {
    @Test
    void rebuildUsesNewestEventWhenProjectionCheckpointIsMissing() {
        CognitiveState state = new CognitiveState();
        state.importState(new CognitiveState.State(1, List.of(
                new CognitiveState.Event(1, "projection", "active_task", "old", 1),
                new CognitiveState.Event(2, "projection", "active_task", "new", 2)
        ), Map.of(), 3, 2));

        assertEquals("new", state.projection("ACTIVE_TASK"));
    }
}
