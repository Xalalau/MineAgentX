package com.mineagent.engine.construction;

import java.util.Objects;

/** Store and server-owned world/action port for exactly one companion. */
public record ConstructionSession(ConstructionRepository repository, ConstructionExecution.Port port) {
    public ConstructionSession { Objects.requireNonNull(repository); }
    public ConstructionExecution execution() {
        return new ConstructionExecution(repository, Objects.requireNonNull(port, "world_port"));
    }
    /** Compatibility query; missing/evicted ledger data is not a terminal receipt. */
    public boolean taskFinished(String taskId) throws java.io.IOException {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task_id");
        String status = port.taskState(taskId);
        return status != null && java.util.Set.of("SUCCESS", "FAILED", "CANCELLED").contains(status)
                && taskFinished(taskId, status);
    }
    /** Call on the server thread with the real terminal callback state, not admission. */
    public boolean taskFinished(String taskId, String terminalState) throws java.io.IOException {
        return execution().taskFinished(taskId, terminalState);
    }
}
