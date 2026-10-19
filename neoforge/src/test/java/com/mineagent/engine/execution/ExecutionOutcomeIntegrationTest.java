package com.mineagent.engine.execution;

import com.mineagent.engine.loop.AgentLoop;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual AgentLoop result policy: uncertainty is neither learned physical success nor learned failure. */
final class ExecutionOutcomeIntegrationTest {
    @ParameterizedTest @ValueSource(strings = {"tool_cancelled", "tool_timeout"})
    void expiredExecutionIsNotFedIntoPlanOrFailureLearning(String code) throws Exception {
        var analyze = AgentLoop.class.getDeclaredMethod("analyzeFailure", String.class);
        analyze.setAccessible(true);
        Object result = analyze.invoke(null, "{\"error\":\"Execution is no longer authoritative\",\"code\":\"" + code + "\"}");
        for (String flag : new String[]{"isFailure", "isSuccess"}) {
            var accessor = result.getClass().getDeclaredMethod(flag);
            accessor.setAccessible(true);
            assertEquals(false, accessor.invoke(result), "expiry must not become an invented physical outcome: " + flag);
        }
    }
}
