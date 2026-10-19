package com.mineagent.tools.skill;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.function.Consumer;

/** Starts a learned sequence in the verifier-backed closed-loop runtime. */
public final class ExecuteSkillTool implements Tool {
    @Override public String name() { return "execute_skill"; }

    @Override public String description() {
        return ToolMessages.text("execute_skill.description");
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("skill_name", ToolMessages.text("execute_skill.parameter.skill_name"))
                .optionalString("overrides_json",
                        ToolMessages.text("execute_skill.parameter.overrides_json"))
                .build();
    }

    @Override public boolean dispatchesAsyncTask() { return true; }
    @Override public int defaultTimeoutSeconds() { return 5; }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String skillName = ToolArgs.getString(args, "skill_name");
        if (skillName == null || skillName.isBlank()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_skill_name")));
            return;
        }
        JsonObject overrides = null;
        String overrideText = ToolArgs.getString(args, "overrides_json", null);
        if (overrideText != null && !overrideText.isBlank()) {
            try {
                var parsed = JsonParser.parseString(overrideText);
                if (!parsed.isJsonObject()) {
                    reply.accept(ToolArgs.errorJson(ToolMessages.text("error.overrides_json_must_encode_an_object")));
                    return;
                }
                overrides = parsed.getAsJsonObject();
            } catch (RuntimeException malformed) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_overrides_json_0", String.valueOf(malformed.getMessage()))));
                return;
            }
        }
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.agent_loop_is_unavailable")));
            return;
        }
        var result = loop.startSkill(skillName, overrides);
        JsonObject response = new JsonObject();
        if (!result.accepted()) {
            response.addProperty("error", result.message());
            if (result.runId() != null) response.addProperty("active_run_id", result.runId());
        } else {
            response.addProperty("success", true);
            response.addProperty("async", true);
            response.addProperty("skill_run_id", result.runId());
            response.addProperty("message", result.message());
        }
        reply.accept(response.toString());
    }
}
