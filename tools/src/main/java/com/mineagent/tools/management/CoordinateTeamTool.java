package com.mineagent.tools.management;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.cognition.TeamBlackboard;
import com.mineagent.engine.task.TaskContext;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Explicit coordination channel backed by live, owner-scoped team state. */
public final class CoordinateTeamTool implements Tool {
    private static final int MAX_TEXT = 512;
    private static final java.util.Set<String> PRIORITIES =
            java.util.Set.of("low", "medium", "high", "critical");

    @Override public String name() { return "coordinate_team"; }

    @Override
    public String description() {
        return ToolMessages.text("coordinate_team.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new java.util.LinkedHashMap<>(Schema.object()
                .string("action", ToolMessages.text("coordinate_team.parameter.action"))
                .optionalString("objective", ToolMessages.text("coordinate_team.parameter.objective"))
                .optionalString("role", ToolMessages.text("coordinate_team.parameter.role"))
                .optionalString("request", ToolMessages.text("coordinate_team.parameter.request"))
                .optionalString("priority", ToolMessages.text("coordinate_team.parameter.priority"))
                .optionalString("target", ToolMessages.text("coordinate_team.parameter.target"))
                .build());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties = new java.util.LinkedHashMap<>(
                (Map<String, Map<String, Object>>) schema.get("properties"));
        properties.put("action", Map.of("type", "string",
                "enum", List.of("status", "commit", "request_support", "clear"),
                "description", ToolMessages.text("coordinate_team.parameter.action_detail")));
        schema.put("properties", java.util.Collections.unmodifiableMap(properties));
        return java.util.Collections.unmodifiableMap(schema);
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String action = ToolArgs.getString(args, "action");
        if (action == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.action_is_required")));
            return;
        }
        long gameTick;
        try {
            gameTick = TaskContext.serverPlayer(player).level().getGameTime();
        } catch (RuntimeException failure) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.companion_is_no_longer_active")));
            return;
        }

        JsonObject result = new JsonObject();
        switch (action) {
            case "status" -> {
                result.addProperty("success", true);
                result.addProperty("team", TeamBlackboard.summarize(
                        player.ownerUuid(), player.companionId(), gameTick));
            }
            case "commit" -> {
                String objective = bounded(args, "objective");
                if (objective == null) {
                    reply.accept(ToolArgs.errorJson(ToolMessages.text("error.objective_is_required_and_must_be_at_most_0_characters", MAX_TEXT)));
                    return;
                }
                TeamBlackboard.ClaimResult claim = TeamBlackboard.tryCommit(
                        player.ownerUuid(), player.companionId(), objective,
                        defaulted(bounded(args, "role"), "flex"),
                        bounded(args, "target"), gameTick);
                result.addProperty("success", claim.accepted());
                result.addProperty("committed", claim.accepted());
                result.addProperty("reason", claim.reason());
            }
            case "request_support" -> {
                String request = bounded(args, "request");
                if (request == null) {
                    reply.accept(ToolArgs.errorJson(ToolMessages.text("error.request_is_required_and_must_be_at_most_0_characters", MAX_TEXT)));
                    return;
                }
                String priority = defaulted(bounded(args, "priority"), "high");
                if (!PRIORITIES.contains(priority)) {
                    reply.accept(ToolArgs.errorJson(ToolMessages.text("error.priority_must_be_low_medium_high_or_critical")));
                    return;
                }
                String target = bounded(args, "target");
                TeamBlackboard.requestSupport(player.ownerUuid(), player.companionId(),
                        request, priority, target, gameTick);
                // Support is exceptional and time-sensitive, so it is the one
                // blackboard mutation that explicitly wakes sibling planners.
                MineAgentEngine.broadcastToOtherCompanions(player.companionId(),
                        player.ownerUuid(), ToolMessages.text("team.support", priority, request, target == null ? "" : ToolMessages.text("team.target", target)));
                result.addProperty("success", true);
                result.addProperty("support_requested", request);
            }
            case "clear" -> {
                TeamBlackboard.clearCommitment(player.ownerUuid(), player.companionId());
                result.addProperty("success", true);
                result.addProperty("cleared", true);
            }
            default -> {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_action_0", action)));
                return;
            }
        }
        reply.accept(result.toString());
    }

    private static String bounded(JsonObject args, String name) {
        String value = ToolArgs.getString(args, name, null);
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) return null;
        return value.trim();
    }

    private static String defaulted(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
