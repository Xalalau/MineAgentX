package com.mineagent.tools.planning;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.task.TaskContext;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Controls the bounded unfamiliar-mechanism learning and adaptation loop. */
public final class ExploreMechanismTool implements Tool {
    @Override public String name() { return "explore_mechanism"; }

    @Override public String description() {
        return ToolMessages.text("explore_mechanism.description");
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", ToolMessages.text("explore_mechanism.parameter.mode"))
                .optionalString("experiment_id", ToolMessages.text("explore_mechanism.parameter.experiment_id"))
                .optionalString("subject", ToolMessages.text("explore_mechanism.parameter.subject"))
                .optionalString("hypothesis", ToolMessages.text("explore_mechanism.parameter.hypothesis"))
                .optionalString("probe_tool", ToolMessages.text("explore_mechanism.parameter.probe_tool"))
                .optionalString("probe_arguments", ToolMessages.text("explore_mechanism.parameter.probe_arguments"))
                .optionalString("expected_subject", ToolMessages.text("explore_mechanism.parameter.expected_subject"))
                .optionalString("expected_predicate", ToolMessages.text("explore_mechanism.parameter.expected_predicate"))
                .optionalString("expected_value", ToolMessages.text("explore_mechanism.parameter.expected_value"))
                .optionalString("context", ToolMessages.text("explore_mechanism.parameter.context"))
                .optionalString("risk", ToolMessages.text("explore_mechanism.parameter.risk"))
                .optionalBoolean("reversible", ToolMessages.text("explore_mechanism.parameter.reversible"))
                .optionalString("compensation_tool", ToolMessages.text("explore_mechanism.parameter.compensation_tool"))
                .optionalString("compensation_arguments", ToolMessages.text("explore_mechanism.parameter.compensation_arguments"))
                .optionalInteger("estimated_cost", ToolMessages.text("explore_mechanism.parameter.estimated_cost"), 1, 12)
                .nullableNumber("information_gain", ToolMessages.text("explore_mechanism.parameter.information_gain"))
                .optionalString("candidate_probes", ToolMessages.text("explore_mechanism.parameter.candidate_probes"))
                .optionalString("reason", ToolMessages.text("explore_mechanism.parameter.reason"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.agent_loop_is_unavailable")));
            return;
        }
        String mode = ToolArgs.getString(args, "mode", "status")
                .toLowerCase(Locale.ROOT);
        long gameTick;
        try {
            gameTick = TaskContext.serverPlayer(player).level().getGameTime();
        } catch (RuntimeException unavailable) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.live_server_state_is_unavailable")));
            return;
        }
        switch (mode) {
            case "propose" -> propose(args, loop.mechanismExplorer(), gameTick, reply);
            case "recommend" -> recommend(args, loop.mechanismExplorer(), gameTick, reply);
            case "abort" -> {
                boolean aborted = loop.mechanismExplorer().abort(
                        ToolArgs.getString(args, "experiment_id", null),
                        ToolArgs.getString(args, "reason", ToolMessages.text("experiment.aborted")), gameTick);
                JsonObject result = new JsonObject();
                result.addProperty("success", aborted);
                if (!aborted) result.addProperty("error", ToolMessages.text("experiment.not_found"));
                reply.accept(result.toString());
            }
            case "status" -> status(loop.mechanismExplorer(),
                    ToolArgs.getString(args, "experiment_id", null), reply);
            default -> reply.accept(ToolArgs.errorJson(ToolMessages.text("error.mode_must_be_propose_recommend_status_or_abort")));
        }
    }

    private static void propose(JsonObject args, MechanismExplorer explorer,
                                long gameTick, Consumer<String> reply) {
        String subject = ToolArgs.getString(args, "subject");
        String hypothesis = ToolArgs.getString(args, "hypothesis");
        String probeTool = ToolArgs.getString(args, "probe_tool");
        if (subject == null || hypothesis == null || probeTool == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.propose_requires_subject_hypothesis_and_probe_tool")));
            return;
        }
        MechanismExplorer.Risk risk = parseRisk(ToolArgs.getString(args, "risk", "low"));
        var proposal = explorer.propose(subject, hypothesis, probeTool,
                ToolArgs.getString(args, "probe_arguments", "{}"),
                ToolArgs.getString(args, "expected_subject", null),
                ToolArgs.getString(args, "expected_predicate", null),
                ToolArgs.getString(args, "expected_value", null),
                risk, ToolArgs.getString(args, "context",
                        subject + "|" + ToolArgs.getString(args, "probe_arguments", "{}")),
                ToolArgs.getBool(args, "reversible", risk == MechanismExplorer.Risk.LOW),
                ToolArgs.getString(args, "compensation_tool", null),
                ToolArgs.getString(args, "compensation_arguments", "{}"),
                ToolArgs.getInt(args, "estimated_cost",
                        risk == MechanismExplorer.Risk.MEDIUM ? 4 : 1),
                ToolArgs.getDouble(args, "information_gain", 0.6), gameTick);
        JsonObject result = new JsonObject();
        result.addProperty("success", proposal.accepted());
        result.addProperty("message", proposal.message());
        if (proposal.experiment() != null) {
            result.add("experiment", json(proposal.experiment()));
        }
        if (!proposal.accepted()) result.addProperty("error", proposal.message());
        reply.accept(result.toString());
    }

    private static void recommend(JsonObject args, MechanismExplorer explorer,
                                  long gameTick, Consumer<String> reply) {
        String subject = ToolArgs.getString(args, "subject");
        JsonArray values = ToolArgs.getArray(args, "candidate_probes");
        if (subject == null || values == null || values.isEmpty() || values.size() > 12) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.recommend_requires_subject_and_1_12_candidate_probes")));
            return;
        }
        java.util.ArrayList<MechanismExplorer.ProbeCandidate> candidates = new java.util.ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String tool = ToolArgs.getString(value, "tool");
            if (tool == null) continue;
            MechanismExplorer.Risk risk = parseRisk(ToolArgs.getString(value, "risk", "low"));
            candidates.add(new MechanismExplorer.ProbeCandidate(tool,
                    jsonObjectString(value, "args"), risk,
                    ToolArgs.getBool(value, "reversible", risk == MechanismExplorer.Risk.LOW),
                    ToolArgs.getString(value, "compensation_tool", null),
                    jsonObjectString(value, "compensation_args"),
                    ToolArgs.getInt(value, "estimated_cost",
                            risk == MechanismExplorer.Risk.MEDIUM ? 4 : 1),
                    ToolArgs.getDouble(value, "information_gain", 0.5)));
        }
        var recommendation = explorer.recommend(candidates, subject, gameTick);
        JsonObject result = new JsonObject();
        result.addProperty("success", recommendation.candidate() != null);
        result.addProperty("message", recommendation.message());
        if (recommendation.candidate() != null) {
            var candidate = recommendation.candidate();
            JsonObject selected = new JsonObject();
            selected.addProperty("tool", candidate.tool());
            selected.addProperty("arguments", candidate.arguments());
            selected.addProperty("risk", candidate.risk().name().toLowerCase(Locale.ROOT));
            selected.addProperty("reversible", candidate.reversible());
            if (candidate.compensationTool() != null) {
                selected.addProperty("compensation_tool", candidate.compensationTool());
                selected.addProperty("compensation_arguments", candidate.compensationArguments());
            }
            selected.addProperty("estimated_cost", candidate.estimatedCost());
            selected.addProperty("information_gain", candidate.estimatedInformationGain());
            selected.addProperty("score", recommendation.score());
            result.add("candidate", selected);
        } else result.addProperty("error", recommendation.message());
        reply.accept(result.toString());
    }

    private static void status(MechanismExplorer explorer, String experimentId,
                               Consumer<String> reply) {
        JsonObject result = new JsonObject();
        if (experimentId != null) {
            var experiment = explorer.get(experimentId);
            if (experiment == null) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_experiment_0", experimentId)));
                return;
            }
            result.add("experiment", json(experiment));
        } else {
            JsonArray values = new JsonArray();
            explorer.all().stream().skip(Math.max(0, explorer.all().size() - 8L))
                    .forEach(experiment -> values.add(json(experiment)));
            result.add("experiments", values);
        }
        result.addProperty("success", true);
        reply.accept(result.toString());
    }

    private static JsonObject json(MechanismExplorer.Experiment value) {
        JsonObject out = new JsonObject();
        out.addProperty("id", value.id());
        out.addProperty("subject", value.subject());
        out.addProperty("hypothesis", value.hypothesis());
        out.addProperty("probe_tool", value.probeTool());
        out.addProperty("expected", value.expectedSubject() + " "
                + value.expectedPredicate() + "=" + value.expectedValue());
        out.addProperty("risk", value.risk().name().toLowerCase(Locale.ROOT));
        out.addProperty("status", value.status().name().toLowerCase(Locale.ROOT));
        out.addProperty("context", value.contextKey());
        out.addProperty("cost", value.estimatedCost());
        out.addProperty("information_gain", value.estimatedInformationGain());
        out.addProperty("reversible", value.reversible());
        if (value.compensationTool() != null) {
            out.addProperty("compensation_tool", value.compensationTool());
            out.addProperty("compensation_attempted", value.compensationAttempted());
            out.addProperty("compensation_succeeded", value.compensationSucceeded());
        }
        out.addProperty("evidence", value.evidence());
        return out;
    }

    private static MechanismExplorer.Risk parseRisk(String value) {
        return switch (value == null ? "low" : value.toLowerCase(Locale.ROOT)) {
            case "high" -> MechanismExplorer.Risk.HIGH;
            case "medium" -> MechanismExplorer.Risk.MEDIUM;
            default -> MechanismExplorer.Risk.LOW;
        };
    }

    private static String jsonObjectString(JsonObject source, String key) {
        JsonObject value = ToolArgs.getObject(source, key);
        return value == null ? "{}" : value.toString();
    }
}
