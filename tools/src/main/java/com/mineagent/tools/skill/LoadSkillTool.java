package com.mineagent.tools.skill;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.skill.SkillInfo;
import com.mineagent.api.agent.skill.SkillRegistry;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Load a skill document into the conversation context.
 * Skills teach the AI how to use specific capabilities or play mods.
 *
 * <p>This is a <b>sync</b> tool - replies immediately with the skill content.
 */
public class LoadSkillTool implements Tool {

    @Override
    public String name() { return "load_skill"; }

    @Override
    public String description() {
        return ToolMessages.text("load_skill.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("skill_name", ToolMessages.text("load_skill.parameter.skill_name"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String skillName = ToolArgs.getString(args, "skill_name");
        if (skillName == null) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_skill_name")));
            return;
        }

        var skill = SkillRegistry.get(skillName);
        if (skill.isPresent()) {
            reply.accept(skill.get().content());
            return;
        }

        // Learned skills are companion-specific and live in AgentLoop rather
        // than the global documentation registry. Exposing both through this
        // one tool removes a retrieval split that previously made learned
        // experience impossible to load by name.
        var loop = com.mineagent.engine.task.TaskContext.agentLoop(player);
        var learned = loop == null ? java.util.Optional
                .<com.mineagent.engine.skill.SkillLibrary.Skill>empty()
                : loop.skillLibrary().get(skillName);
        if (learned.isPresent()) {
            var value = learned.get();
            JsonObject result = new JsonObject();
            result.addProperty("source", "learned_experience");
            result.addProperty("skill_name", value.name());
            result.addProperty("description", value.description());
            result.addProperty("trigger_condition", value.triggerCondition());
            result.addProperty("success_rate", value.successRate());
            result.addProperty("invocations", value.invocations());
            try {
                result.add("action_sequence",
                        com.google.gson.JsonParser.parseString(value.actionSequence()));
            } catch (RuntimeException malformedSequence) {
                result.addProperty("action_sequence", value.actionSequence());
                result.addProperty("warning", ToolMessages.text("skills.invalid_sequence"));
            }
            reply.accept(result.toString());
            return;
        }

        {
            // List available skills
            StringBuilder available = new StringBuilder();
            available.append("{\"error\":").append(new com.google.gson.Gson().toJson(ToolMessages.text("skills.not_found", skillName)));
            available.append(",\"available_skills\":[");
            boolean first = true;
            for (SkillInfo s : SkillRegistry.all()) {
                if (!first) available.append(",");
                available.append(new com.google.gson.Gson().toJson(s.name()));
                first = false;
            }
            if (loop != null) {
                for (var learnedSkill : loop.skillLibrary().allSkills()) {
                    if (!first) available.append(",");
                    available.append(new com.google.gson.Gson().toJson(learnedSkill.name()));
                    first = false;
                }
            }
            available.append("]}");
            reply.accept(available.toString());
            return;
        }
    }
}
