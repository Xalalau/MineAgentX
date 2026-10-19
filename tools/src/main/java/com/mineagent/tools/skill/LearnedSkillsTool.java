package com.mineagent.tools.skill;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * List learned skills — query the AI's personal skill library.
 *
 * <p>Backed by {@link com.mineagent.engine.skill.SkillLibrary}. The
 * AgentLoop automatically records successful tool-call sequences as
 * skills (Voyager-style). This tool lets the LLM recall what it has
 * already learned, so it can reuse a skill instead of re-planning
 * from scratch.
 *
 * <p>This is the "Augments" primitive (Mindcraft dev branch): the AI
 * builds up its own library of reusable action patterns through
 * experience. Over time, common tasks become one-tool-call lookups
 * instead of multi-step LLM reasoning.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class LearnedSkillsTool implements Tool {

    @Override
    public String name() { return "list_learned_skills"; }

    @Override
    public String description() {
        return ToolMessages.text("list_learned_skills.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("query",
                        ToolMessages.text("list_learned_skills.parameter.query"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.agent_loop_unavailable")));
            return;
        }

        var skillLib = loop.skillLibrary();
        String query = ToolArgs.getString(args, "query", "");
        var skills = skillLib.relevant(query, query.isBlank() ? 5 : 3);

        if (skills.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("count", 0);
            result.addProperty("message", skillLib.size() == 0
                    ? ToolMessages.text("skills.none")
                    : ToolMessages.text("skills.no_match"));
            reply.accept(result.toString());
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("count", skills.size());
        result.addProperty("total_available", skillLib.size());

        JsonArray arr = new JsonArray();
        for (var skill : skills) {
            JsonObject s = new JsonObject();
            s.addProperty("name", skill.name());
            s.addProperty("description", skill.description());
            s.addProperty("success_rate",
                    String.format(Locale.ROOT, "%.0f%%", skill.successRate() * 100));
            s.addProperty("invocations", skill.invocations());
            arr.add(s);
        }
        result.add("skills", arr);
        result.addProperty("hint",
                ToolMessages.text("skills.execute_hint"));
        reply.accept(result.toString());
    }
}
