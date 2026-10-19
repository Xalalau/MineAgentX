package com.mineagent.engine.skill;

import com.mineagent.engine.i18n.ContextMessages;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Closed-loop executor for learned, parameterized tool sequences.
 *
 * <p>Only one step is dispatched at a time. Asynchronous steps wait for the
 * scheduler's terminal result, and declared effects must be observed in the
 * semantic world model before the next step can start. A failed or unverifiable
 * step stops with a structured replan request instead of blindly replaying the
 * remaining actions against a changed world.
 */
public final class SkillRuntime {
    public enum Status {
        IDLE, RUNNING, WAITING_TASK, VERIFYING,
        SUCCEEDED, NEEDS_REPLAN, FAILED, CANCELLED
    }

    public enum FailurePolicy { REPLAN, RETRY_ONCE, ABORT }
    public enum FailureKind { NONE, ACTION, POSTCONDITION }

    /** Authority is structural metadata, never inferred from evidence or player prose. */
    public enum Disposition {
        VERIFIED, UNVERIFIED, CANCELLED;
        public boolean learnable() { return this == VERIFIED; }
    }

    public record Condition(String subject, String predicate, String value,
                            double minimumConfidence) {
        public Condition {
            subject = normalize(subject, "unknown");
            predicate = normalize(predicate, "observed");
            value = normalize(value, "*");
            minimumConfidence = unit(minimumConfidence);
        }
    }

    public record Step(String tool, JsonObject arguments,
                       List<Condition> preconditions,
                       List<Condition> expectedEffects,
                       FailurePolicy failurePolicy,
                       int maxAttempts, long timeoutTicks) {
        public Step {
            tool = normalizeTool(tool);
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
            expectedEffects = expectedEffects == null ? List.of() : List.copyOf(expectedEffects);
            failurePolicy = failurePolicy == null ? FailurePolicy.REPLAN : failurePolicy;
            maxAttempts = Math.max(1, Math.min(3, maxAttempts));
            timeoutTicks = Math.max(20L, Math.min(12_000L, timeoutTicks));
        }
    }

    public record DispatchResult(boolean accepted, boolean asynchronous,
                                 boolean success, String evidence, Disposition disposition) {
        /** Legacy protocol codes remain compatible; ordinary evidence is authoritative, not parsed as prose. */
        public DispatchResult(boolean accepted, boolean asynchronous, boolean success, String evidence) {
            this(accepted, asynchronous, success, evidence, legacyDisposition(evidence));
        }
        private static Disposition legacyDisposition(String evidence) {
            try {
                JsonElement value = JsonParser.parseString(evidence);
                JsonElement code = value.isJsonObject() ? value.getAsJsonObject().get("code") : null;
                if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()) {
                    return switch (code.getAsString()) {
                        case "tool_cancelled" -> Disposition.CANCELLED;
                        case "tool_timeout" -> Disposition.UNVERIFIED;
                        default -> Disposition.VERIFIED;
                    };
                }
            } catch (RuntimeException notStructured) {
                // Only exact top-level protocol fields carry authority metadata.
            }
            return Disposition.VERIFIED;
        }
        public DispatchResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            evidence = normalize(evidence, success ? "accepted" : ContextMessages.text("skill.dispatchFailure"));
        }
    }

    public record StartResult(boolean accepted, String runId, String message) {}

    public record Snapshot(String runId, String skillName, Status status,
                           int stepIndex, int stepCount, int attempt,
                           String activeTool, String lastEvidence,
                           long startedTick, long updatedTick, Disposition disposition,
                           FailureKind failureKind) {
        public Snapshot(String runId, String skillName, Status status, int stepIndex,
                        int stepCount, int attempt, String activeTool, String lastEvidence,
                        long startedTick, long updatedTick, Disposition disposition) {
            this(runId, skillName, status, stepIndex, stepCount, attempt, activeTool,
                    lastEvidence, startedTick, updatedTick, disposition, FailureKind.NONE);
        }
        public Snapshot(String runId, String skillName, Status status, int stepIndex,
                        int stepCount, int attempt, String activeTool, String lastEvidence,
                        long startedTick, long updatedTick) {
            this(runId, skillName, status, stepIndex, stepCount, attempt, activeTool,
                    lastEvidence, startedTick, updatedTick,
                    status == Status.CANCELLED ? Disposition.CANCELLED : Disposition.VERIFIED);
        }
        public boolean terminal() {
            return status == Status.SUCCEEDED || status == Status.NEEDS_REPLAN
                    || status == Status.FAILED || status == Status.CANCELLED;
        }
    }

    @FunctionalInterface
    public interface ActionDispatcher {
        DispatchResult dispatch(String actionId, String tool, JsonObject arguments);

        /**
         * Best-effort cancellation for an asynchronous inner task.  The
         * default keeps third-party test/integration dispatchers source
         * compatible; the engine implementation cancels the exact task id
         * through PriorityAuction so a timed-out skill cannot leave a body
         * task running while the planner has already moved on.
         */
        default void cancel(String actionId) { }
    }

    @FunctionalInterface
    public interface CompletionSink {
        void completed(Snapshot snapshot);
    }

    private static final int MAX_STEPS = 24;
    private static final Set<String> NEVER_NEST = Set.of(
            "execute_skill", "explore_mechanism", "todowrite",
            "task_stop", "coordinate_team");

    private final SkillLibrary library;
    private final SemanticWorldModel worldModel;
    private final ActionDispatcher dispatcher;
    private final CompletionSink completionSink;

    private String runId;
    private String skillName;
    private List<Step> steps = List.of();
    private Status status = Status.IDLE;
    private Disposition disposition = Disposition.UNVERIFIED;
    private FailureKind failureKind = FailureKind.NONE;
    private int stepIndex;
    private int attempt;
    private String activeActionId;
    private String lastEvidence = "idle";
    private long startedTick;
    private long updatedTick;
    private long stepStartedTick;
    private long verificationDeadlineTick;
    private long worldRevisionAtDispatch;
    private boolean taskPaused;
    private long taskPausedAtTick;

    public SkillRuntime(SkillLibrary library, SemanticWorldModel worldModel,
                        ActionDispatcher dispatcher, CompletionSink completionSink) {
        this.library = Objects.requireNonNull(library, "library");
        this.worldModel = Objects.requireNonNull(worldModel, "worldModel");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.completionSink = Objects.requireNonNull(completionSink, "completionSink");
    }

    public synchronized StartResult start(String requestedSkill,
                                          JsonObject overrides,
                                          long gameTick) {
        if (active()) {
            return new StartResult(false, runId,
                    ContextMessages.text("skill.busy") + skillName);
        }
        SkillLibrary.Skill learned = library.get(requestedSkill).orElse(null);
        if (learned == null) {
            return new StartResult(false, null, ContextMessages.text("skill.notFound") + requestedSkill);
        }
        List<Step> decoded;
        try {
            decoded = decode(learned.actionSequence(), overrides);
        } catch (IllegalArgumentException malformed) {
            return new StartResult(false, null,
                    ContextMessages.text("skill.cannotExecute") + malformed.getMessage());
        }
        if (decoded.isEmpty()) {
            return new StartResult(false, null, ContextMessages.text("skill.noActions"));
        }
        runId = "skill-" + UUID.randomUUID().toString().substring(0, 12);
        skillName = learned.name();
        steps = decoded;
        status = Status.RUNNING;
        disposition = Disposition.UNVERIFIED;
        failureKind = FailureKind.NONE;
        stepIndex = 0;
        attempt = 0;
        activeActionId = null;
        lastEvidence = "accepted";
        startedTick = Math.max(0L, gameTick);
        updatedTick = startedTick;
        stepStartedTick = startedTick;
        verificationDeadlineTick = 0L;
        taskPaused = false;
        taskPausedAtTick = 0L;
        worldRevisionAtDispatch = worldModel.revision();
        return new StartResult(true, runId,
                ContextMessages.text("skill.acceptedSteps", steps.size()));
    }

    /** Advance at most one transition per server tick. */
    public synchronized void tick(long gameTick) {
        updatedTick = Math.max(updatedTick, gameTick);
        if (!active()) return;
        Step step = steps.get(stepIndex);

        if (status == Status.WAITING_TASK && !taskPaused
                && gameTick - stepStartedTick > step.timeoutTicks()) {
            terminal(Status.NEEDS_REPLAN, ContextMessages.text("skill.timeout", step.timeoutTicks()),
                    gameTick, Disposition.UNVERIFIED);
            return;
        }
        if (status == Status.VERIFYING) {
            if (effectsVerified(step, gameTick)) completeStep(gameTick);
            else if (gameTick > verificationDeadlineTick) {
                failStep(ContextMessages.text("skill.postcondition"), gameTick, FailureKind.POSTCONDITION);
            }
            return;
        }
        if (status != Status.RUNNING) return;

        List<Condition> missing = missing(step.preconditions(), 0L, gameTick);
        if (!missing.isEmpty()) {
            terminal(Status.NEEDS_REPLAN,
                    ContextMessages.text("skill.precondition") + describe(missing.get(0)), gameTick);
            return;
        }

        attempt++;
        stepStartedTick = gameTick;
        worldRevisionAtDispatch = worldModel.revision();
        activeActionId = runId + ":" + stepIndex + ":" + attempt;
        String dispatchedRun = runId;
        String dispatchedAction = activeActionId;
        // Arm exact-action callbacks before dispatch, which may complete synchronously.
        status = Status.WAITING_TASK;
        DispatchResult result;
        try {
            result = dispatcher.dispatch(activeActionId, step.tool(),
                    step.arguments().deepCopy());
        } catch (Throwable failure) {
            result = new DispatchResult(false, false, false,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(), Disposition.UNVERIFIED);
        }
        // Dispatch may synchronously revoke this run or let a completion sink start another.
        if (status != Status.WAITING_TASK || !Objects.equals(runId, dispatchedRun)
                || !Objects.equals(activeActionId, dispatchedAction)) return;
        lastEvidence = result.evidence();
        if (!result.disposition().learnable()) {
            terminal(result.disposition() == Disposition.CANCELLED ? Status.CANCELLED : Status.NEEDS_REPLAN,
                    result.evidence(), gameTick, result.disposition());
            return;
        }
        if (!result.accepted() || !result.success()) {
            failStep(result.evidence(), gameTick);
        } else if (result.asynchronous()) {
            status = Status.WAITING_TASK;
        } else if (step.expectedEffects().isEmpty()) {
            completeStep(gameTick);
        } else {
            status = Status.VERIFYING;
            verificationDeadlineTick = saturatedAdd(gameTick, 200L);
        }
    }

    /** Accept only the terminal event for the exact internally dispatched step. */
    public synchronized boolean onTaskProgress(String taskId, TaskState taskState,
                                               long gameTick) {
        if (status != Status.WAITING_TASK || activeActionId == null
                || !activeActionId.equals(taskId)) return false;
        if (taskState == TaskState.PAUSED && !taskPaused) {
            taskPaused = true;
            taskPausedAtTick = gameTick;
        } else if (taskState == TaskState.RUNNING && taskPaused) {
            stepStartedTick = saturatedAdd(stepStartedTick,
                    Math.max(0L, gameTick - taskPausedAtTick));
            taskPaused = false;
            taskPausedAtTick = 0L;
        }
        return true;
    }

    /** Accept only the terminal event for the exact internally dispatched step. */
    public synchronized boolean onTaskFinished(String taskId, TaskState taskState,
                                               String evidence, long gameTick) {
        if (taskState != TaskState.SUCCESS && taskState != TaskState.FAILED
                && taskState != TaskState.CANCELLED) return false;
        if (status != Status.WAITING_TASK || activeActionId == null
                || !activeActionId.equals(taskId)) return false;
        lastEvidence = normalize(evidence, taskState.name());
        taskPaused = false;
        taskPausedAtTick = 0L;
        if (taskState == TaskState.CANCELLED) {
            terminal(Status.CANCELLED, lastEvidence, gameTick, Disposition.CANCELLED);
        } else if (taskState != TaskState.SUCCESS) {
            failStep(lastEvidence, gameTick);
        } else if (steps.get(stepIndex).expectedEffects().isEmpty()) {
            completeStep(gameTick);
        } else {
            status = Status.VERIFYING;
            verificationDeadlineTick = saturatedAdd(gameTick, 200L);
            updatedTick = Math.max(updatedTick, gameTick);
        }
        return true;
    }

    public synchronized void cancel(String reason, long gameTick) {
        if (!active()) return;
        terminal(Status.CANCELLED, normalize(reason, "cancelled"), gameTick, Disposition.CANCELLED);
    }

    public synchronized boolean active() {
        return status == Status.RUNNING || status == Status.WAITING_TASK
                || status == Status.VERIFYING;
    }

    public synchronized boolean ownsAction(String actionId) {
        return actionId != null && actionId.equals(activeActionId);
    }

    public synchronized Snapshot snapshot() {
        String activeTool = stepIndex >= 0 && stepIndex < steps.size()
                ? steps.get(stepIndex).tool() : null;
        return new Snapshot(runId, skillName, status, stepIndex,
                steps.size(), attempt, activeTool, lastEvidence,
                startedTick, updatedTick, disposition, failureKind);
    }

    public synchronized String summarizeForPrompt() {
        if (status == Status.IDLE) return ContextMessages.text("skill.idle");
        Snapshot value = snapshot();
        return ContextMessages.text("skill.heading") + value.runId() + " skill=" + value.skillName()
                + " status=" + value.status().name().toLowerCase(Locale.ROOT)
                + " step=" + Math.min(value.stepIndex() + 1, value.stepCount())
                + "/" + value.stepCount() + " tool=" + value.activeTool()
                + " evidence=" + value.lastEvidence() + "\n";
    }

    private void completeStep(long gameTick) {
        lastEvidence = ContextMessages.text("skill.stepVerified") + steps.get(stepIndex).tool();
        stepIndex++;
        attempt = 0;
        activeActionId = null;
        updatedTick = Math.max(updatedTick, gameTick);
        if (stepIndex >= steps.size()) {
            library.recordResult(skillName, true);
            terminal(Status.SUCCEEDED, ContextMessages.text("skill.verified"), gameTick);
        } else {
            status = Status.RUNNING;
        }
    }

    private void failStep(String evidence, long gameTick) {
        failStep(evidence, gameTick, FailureKind.ACTION);
    }

    private void failStep(String evidence, long gameTick, FailureKind kind) {
        failureKind = kind;
        Step step = steps.get(stepIndex);
        lastEvidence = normalize(evidence, ContextMessages.text("skill.stepFailed"));
        String actionToCancel = activeActionId;
        boolean cancelAsync = status == Status.WAITING_TASK;
        updatedTick = Math.max(updatedTick, gameTick);
        if (step.failurePolicy() == FailurePolicy.RETRY_ONCE
                && attempt < step.maxAttempts() && transientFailure(lastEvidence)) {
            activeActionId = null;
            status = Status.RUNNING;
            if (cancelAsync) cancelDispatchedAction(actionToCancel);
            return;
        }
        library.recordResult(skillName, false);
        Status terminal = step.failurePolicy() == FailurePolicy.ABORT
                ? Status.FAILED : Status.NEEDS_REPLAN;
        terminal(terminal, ContextMessages.text("skill.step") + (stepIndex + 1) + " " + step.tool()
                + ContextMessages.text("skill.failed") + lastEvidence, gameTick);
    }

    private void terminal(Status terminalStatus, String evidence, long gameTick) {
        terminal(terminalStatus, evidence, gameTick, Disposition.VERIFIED);
    }

    private void terminal(Status terminalStatus, String evidence, long gameTick,
                          Disposition terminalDisposition) {
        disposition = terminalDisposition;
        String actionToCancel = activeActionId;
        boolean cancelAsync = status == Status.WAITING_TASK;
        status = terminalStatus;
        lastEvidence = normalize(evidence, terminalStatus.name());
        activeActionId = null;
        updatedTick = Math.max(updatedTick, gameTick);
        Snapshot completed = snapshot();
        if (cancelAsync) cancelDispatchedAction(actionToCancel);
        completionSink.completed(completed);
    }

    private void cancelDispatchedAction(String actionId) {
        if (actionId == null || actionId.isBlank()) return;
        try {
            dispatcher.cancel(actionId);
        } catch (Throwable failure) {
            // Cancellation is a safety cleanup. Never let a platform-specific
            // cancellation failure take down the server tick or hide the
            // already-recorded skill failure.
            // Cleanup can reenter and start a different run; never write into its evidence.
            System.err.println("[MineAgent] " + actionId + ContextMessages.text("skill.cancelFailure")
                    + failure.getMessage());
        }
    }

    private boolean effectsVerified(Step step, long gameTick) {
        if (step.expectedEffects().isEmpty()) return true;
        // A declared postcondition must be newer than the action. Merely
        // finding the same old fact would falsely verify a no-op replay.
        return missing(step.expectedEffects(), stepStartedTick, gameTick).isEmpty()
                && worldModel.revision() > worldRevisionAtDispatch;
    }

    private List<Condition> missing(List<Condition> conditions,
                                    long notBeforeTick, long gameTick) {
        if (conditions == null || conditions.isEmpty()) return List.of();
        ArrayList<Condition> result = new ArrayList<>();
        for (Condition condition : conditions) {
            if (!worldModel.matches(condition.subject(), condition.predicate(),
                    condition.value(), notBeforeTick, condition.minimumConfidence(),
                    gameTick)) result.add(condition);
        }
        return List.copyOf(result);
    }

    private static List<Step> decode(String sequenceJson, JsonObject overrides) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(sequenceJson);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(ContextMessages.text("skill.invalidJson"), malformed);
        }
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException(ContextMessages.text("skill.array"));
        }
        JsonArray array = parsed.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_STEPS) {
            throw new IllegalArgumentException(ContextMessages.text("skill.sequenceSize")
                    + MAX_STEPS + ContextMessages.text("skill.steps"));
        }
        ArrayList<Step> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new IllegalArgumentException(ContextMessages.text("skill.step") + index + ContextMessages.text("skill.notObject"));
            }
            JsonObject source = array.get(index).getAsJsonObject();
            String tool = string(source, "tool", null);
            tool = normalizeTool(tool);
            if (NEVER_NEST.contains(tool)) {
                throw new IllegalArgumentException(ContextMessages.text("skill.step") + index
                        + ContextMessages.text("skill.control") + tool);
            }
            JsonObject arguments = object(source, "args");
            if (arguments == null) arguments = new JsonObject();
            JsonObject patch = overrides == null ? null : object(overrides,
                    Integer.toString(index));
            if (patch != null) merge(arguments, patch);
            List<Condition> preconditions = conditions(source, "preconditions");
            List<Condition> effects = conditions(source, "expected_effects");
            FailurePolicy policy = switch (string(source, "on_failure", "replan")
                    .toLowerCase(Locale.ROOT)) {
                case "retry", "retry_once" -> FailurePolicy.RETRY_ONCE;
                case "abort" -> FailurePolicy.ABORT;
                default -> FailurePolicy.REPLAN;
            };
            int maxAttempts = integer(source, "max_attempts",
                    policy == FailurePolicy.RETRY_ONCE ? 2 : 1);
            // Engine tasks receive a 6000-tick authoritative deadline. The
            // enclosing skill must not time out first and leave its body task
            // running without an owner.
            long timeout = integer(source, "timeout_ticks", 6_400);
            result.add(new Step(tool, arguments, preconditions, effects,
                    policy, maxAttempts, timeout));
        }
        return List.copyOf(result);
    }

    private static List<Condition> conditions(JsonObject source, String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) return List.of();
        if (!source.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + ContextMessages.text("skill.fieldArray"));
        }
        ArrayList<Condition> result = new ArrayList<>();
        for (JsonElement element : source.getAsJsonArray(key)) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(key + ContextMessages.text("skill.fieldNotObject"));
            }
            JsonObject value = element.getAsJsonObject();
            String subject = string(value, "subject", null);
            String predicate = string(value, "predicate", null);
            if (subject == null || predicate == null) {
                throw new IllegalArgumentException(key
                        + ContextMessages.text("skill.requiredCondition"));
            }
            result.add(new Condition(subject, predicate,
                    string(value, "value", "*"), number(value,
                    "minimum_confidence", 0.6)));
        }
        return List.copyOf(result);
    }

    private static void merge(JsonObject target, JsonObject patch) {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private static JsonObject object(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return null;
        if (!source.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + ContextMessages.text("skill.fieldObject"));
        }
        return source.getAsJsonObject(key).deepCopy();
    }

    private static String string(JsonObject source, String key, String fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()
                || !source.get(key).isJsonPrimitive()) return fallback;
        try {
            return source.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject source, String key, int fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double number(JsonObject source, String key, double fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean transientFailure(String evidence) {
        String value = normalize(evidence, "").toLowerCase(Locale.ROOT);
        return value.contains("timeout") || value.contains("timed out")
                || value.contains("interrupted") || value.contains("temporar")
                || value.contains("busy") || value.contains("retry");
    }

    private static String describe(Condition condition) {
        return condition.subject() + " " + condition.predicate()
                + "=" + condition.value();
    }

    private static String normalizeTool(String value) {
        String tool = normalize(value, "").toLowerCase(Locale.ROOT);
        if (!tool.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException(ContextMessages.text("skill.invalidTool") + value);
        }
        return tool;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return Math.max(0L, first + second);
    }
}
