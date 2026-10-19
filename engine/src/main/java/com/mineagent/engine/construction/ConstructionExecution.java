package com.mineagent.engine.construction;

import com.google.gson.*;
import com.mineagent.api.construction.ConstructionDesign;
import com.mineagent.api.construction.ConstructionDesign.Point;
import java.io.IOException;
import java.util.*;

/** Progressive execution: one ordinary build batch per model call; world evidence is authoritative. */
public final class ConstructionExecution {
    private static final Gson JSON = new Gson();
    public static final int BATCH_LIMIT = 128;
    public record Batch(String mode, String block, List<Point> positions) {
        public Batch {
            if ((!"place".equals(mode) && !"clear".equals(mode)) || positions == null
                    || positions.isEmpty() || positions.size() > BATCH_LIMIT) throw new IllegalArgumentException("batch");
            ConstructionDesign.resourceId(block); positions = List.copyOf(positions);
        }
    }
    public interface Port extends SiteAssessment.WorldView {
        String dimension();
        Point position();
        long tick();
        String sessionId();
        Map<String, Integer> inventory();
        /** Stable task state IDs; null means not currently in the bounded task ledger. */
        String taskState(String taskId);
        /** Must dispatch through existing physical build actions, never remote world edits. */
        void dispatch(String taskId, Batch batch);
        default List<JsonObject> knownAssets() { return List.of(); }
    }
    private final ConstructionRepository repository;
    private final Port port;
    public ConstructionExecution(ConstructionRepository repository, Port port) {
        this.repository = Objects.requireNonNull(repository); this.port = Objects.requireNonNull(port);
    }
    public JsonObject execute(ConstructionRepository.Revision revision, boolean allowClear, String taskId) throws IOException {
        if (taskId == null || taskId.isBlank() || taskId.length() > 160) throw new IllegalArgumentException("task_id");
        var d = revision.design();
        if (!port.dimension().equals(d.dimension())) return state("different_dimension");
        if (repository.read(d.id(), 0).version() != revision.version()) return state("stale_revision");
        if (reconcile(d.id())) return state("pending");
        // Terminal callbacks carry task IDs, not a caller generation. Never reuse one in this live namespace.
        String after = "";
        while (true) {
            var entries = repository.list(after, 20);
            if (entries.isEmpty()) break;
            for (var entry : entries) for (var event : repository.evidence(entry.id(), 0)) {
                if (event.kind().equals("dispatch") && taskId.equals(event.payload().get("task_id").getAsString())
                        && port.sessionId().equals(event.payload().get("session").getAsString())) return state("task_id_reused");
            }
            after = entries.getLast().id();
        }
        var assessment = SiteAssessment.inspect(d, port, port.position(), port.tick(), port.inventory());
        JsonObject result = state("blocked_site"); result.add("assessment", JSON.toJsonTree(assessment));
        if (!assessment.executable()) return result;
        if (assessment.excavations() > 0 && !allowClear) { result.addProperty("state", "terrain_authorization_required"); return result; }
        if (assessment.deficit().values().stream().anyMatch(n -> n > 0)) { result.addProperty("state", "missing_materials"); return result; }
        String mode = null, block = null;
        List<Point> positions = new ArrayList<>();
        for (var cell : d.cells()) {
            Point p = d.world(cell.position()); var current = port.observe(p);
            if (current == null) return state("site_changed");
            if (cell.clear() ? current.air() : cell.block().equals(current.block())) continue;
            boolean needsClear = !current.air() && (cell.clear() || !current.replaceable() || current.fluid());
            String nextMode = needsClear ? "clear" : "place";
            String nextBlock = needsClear ? "minecraft:air" : cell.block();
            if (mode != null && (!mode.equals(nextMode) || !block.equals(nextBlock))) break;
            // Recheck destructive targets immediately before queueing; normal actions still recheck permissions.
            if (needsClear && (current.asset() || current.protectedBlock() || current.fluid())) return state("site_changed");
            mode = nextMode; block = nextBlock; positions.add(p);
            if (positions.size() == BATCH_LIMIT) break;
        }
        if (positions.isEmpty()) return observe(revision);
        Batch batch = new Batch(mode, block, positions);
        JsonObject intent = new JsonObject(); intent.addProperty("task_id", taskId);
        intent.addProperty("session", port.sessionId()); intent.add("batch", JSON.toJsonTree(batch));
        // Reserve enough evidence capacity for the terminal postcondition before any physical action.
        if (repository.evidence(d.id(), 0).size() >= 254) return state("evidence_limit");
        var event = repository.appendEvidence(d.id(), revision.version(), "dispatch", port.tick(), intent);
        try { port.dispatch(taskId, batch); }
        catch (RuntimeException rejected) {
            var failure = new JsonObject(); failure.addProperty("dispatch_sequence", event.sequence());
            failure.addProperty("task_state", "dispatch_rejected"); failure.addProperty("matched", 0);
            repository.appendEvidence(d.id(), revision.version(), "outcome", port.tick(), failure);
            throw rejected;
        }
        result.addProperty("state", "dispatched"); result.addProperty("task_id", taskId);
        result.addProperty("async", true); result.add("batch", JSON.toJsonTree(batch));
        return result;
    }
    public JsonObject observe(ConstructionRepository.Revision revision) throws IOException {
        var d = revision.design();
        if (!port.dimension().equals(d.dimension())) return state("different_dimension");
        boolean pending = reconcile(d.id());
        int verified = 0, unknown = 0;
        for (var cell : d.cells()) {
            var o = port.observe(d.world(cell.position()));
            if (o == null) unknown++;
            else if (cell.clear() ? o.air() : cell.block().equals(o.block())) verified++;
        }
        JsonObject result = state(pending ? "pending" : verified == d.cells().size() ? "verified" : "incomplete");
        result.addProperty("revision", revision.version()); result.addProperty("verified", verified);
        result.addProperty("total", d.cells().size()); result.addProperty("unknown", unknown);
        return result;
    }
    /** Server-thread terminal callback. The supplied actual terminal state survives task-ledger eviction. */
    public boolean taskFinished(String taskId, String terminalState) throws IOException {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task_id");
        if (!Set.of("SUCCESS", "FAILED", "CANCELLED").contains(Objects.requireNonNull(terminalState)))
            throw new IllegalArgumentException("terminal_state");
        String after = "";
        while (true) {
            var entries = repository.list(after, 20);
            if (entries.isEmpty()) return false;
            for (var entry : entries) {
                var events = repository.evidence(entry.id(), 0);
                Set<Integer> closed = closed(events);
                for (var event : events) {
                    if (event.kind().equals("dispatch") && !closed.contains(event.sequence())
                            && taskId.equals(event.payload().get("task_id").getAsString())
                            && port.sessionId().equals(event.payload().get("session").getAsString())) {
                        recordOutcome(entry.id(), event, terminalState);
                        return true;
                    }
                }
            }
            after = entries.getLast().id();
        }
    }
    private static Set<Integer> closed(List<ConstructionRepository.Evidence> events) {
        Set<Integer> closed = new HashSet<>();
        for (var e : events) if (e.kind().equals("outcome") && e.payload().has("dispatch_sequence"))
            closed.add(e.payload().get("dispatch_sequence").getAsInt());
        return closed;
    }
    private void recordOutcome(String designId, ConstructionRepository.Evidence event, String status) throws IOException {
        var revision = repository.read(designId, event.revision());
        Batch batch = JSON.fromJson(event.payload().get("batch"), Batch.class);
        boolean observable = revision.design().dimension().equals(port.dimension());
        int matched = 0, unknown = 0;
        for (Point p : batch.positions()) {
            var o = observable ? port.observe(p) : null;
            if (o == null) unknown++;
            else if (batch.mode().equals("clear") ? o.air() : batch.block().equals(o.block())) matched++;
        }
        JsonObject outcome = new JsonObject(); outcome.addProperty("dispatch_sequence", event.sequence());
        outcome.addProperty("task_id", event.payload().get("task_id").getAsString());
        outcome.addProperty("task_state", status); outcome.addProperty("matched", matched);
        outcome.addProperty("total", batch.positions().size()); outcome.addProperty("unknown", unknown);
        repository.appendEvidence(designId, event.revision(), "outcome", port.tick(), outcome);
    }
    /** Lifecycle hook: invoke on server ticks or query. Never automatically dispatches more work. */
    public boolean reconcile(String designId) throws IOException {
        List<ConstructionRepository.Evidence> events = repository.evidence(designId, 0);
        Set<Integer> closed = closed(events);
        boolean pending = false;
        for (var e : events) {
            if (!e.kind().equals("dispatch") || closed.contains(e.sequence())) continue;
            var payload = e.payload(); String task = payload.get("task_id").getAsString();
            String status = port.taskState(task);
            boolean sameSession = port.sessionId().equals(payload.get("session").getAsString());
            if (sameSession && (status == null || !Set.of("SUCCESS", "FAILED", "CANCELLED").contains(status))) {
                pending = true; continue;
            }
            recordOutcome(designId, e, sameSession ? status : "interrupted_restart");
        }
        return pending;
    }
    private static JsonObject state(String state) {
        JsonObject value = new JsonObject(); value.addProperty("state", state); return value;
    }
}
