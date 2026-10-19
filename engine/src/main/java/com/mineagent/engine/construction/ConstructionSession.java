package com.mineagent.engine.construction;

import java.util.Objects;

/** Store and server-owned world/action port for exactly one companion. */
public record ConstructionSession(ConstructionRepository repository, ConstructionExecution.Port port) {
    public ConstructionSession { Objects.requireNonNull(repository); }
    public ConstructionExecution execution() {
        return new ConstructionExecution(repository, Objects.requireNonNull(port, "world_port"));
    }
    /** Call on the server thread after TaskStatusTool receives a terminal task update. */
    public boolean taskFinished(String taskId) throws java.io.IOException {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task_id");
        String after = "";
        while (true) {
            var entries = repository.list(after, 20);
            if (entries.isEmpty()) return false;
            for (var entry : entries) {
                for (var event : repository.evidence(entry.id(), 0)) {
                    if (event.kind().equals("dispatch") && event.payload().has("task_id")
                            && taskId.equals(event.payload().get("task_id").getAsString())) {
                        execution().reconcile(entry.id());
                        return true;
                    }
                }
            }
            after = entries.getLast().id();
        }
    }
}
