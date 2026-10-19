package com.mineagent.tools.perception;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Recall memory — query the AI's spatial/semantic memory.
 *
 * <p>Backed by {@link com.mineagent.engine.memory.CognitiveMap} and
 * {@link com.mineagent.engine.memory.PlaceEventMemory}. This lets the
 * LLM actively recall "where did I see iron ore" or "what's nearby"
 * without re-scanning the environment.
 *
 * <p>This is critical for spatial reasoning: instead of re-exploring,
 * the AI recalls "iron was at (10, 64, -5), I can go back there".
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class RecallMemoryTool implements Tool {

    @Override
    public String name() { return "recall_memory"; }

    @Override
    public String description() {
        return ToolMessages.text("recall_memory.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("category",
                        ToolMessages.text("recall_memory.parameter.category"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String category = ToolArgs.getString(args, "category", "");

        var loopOpt = com.mineagent.engine.task.TaskContext.agentLoop(player);
        if (loopOpt == null) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.agent_loop_unavailable")));
            return;
        }

        var cmap = loopOpt.cognitiveMap();
        List<com.mineagent.engine.memory.CognitiveMap.PointOfInterest> results;

        if (category.isBlank()) {
            // All nearby (within 64 blocks of current position)
            var sp = com.mineagent.engine.task.TaskContext.serverPlayer(player);
            results = cmap.findNearby(sp.blockPosition().getX(),
                    sp.blockPosition().getZ(), 64,
                    sp.level().dimension().location().toString());
        } else {
            var sp = com.mineagent.engine.task.TaskContext.serverPlayer(player);
            results = cmap.findByCategory(category.toLowerCase(),
                    sp.level().dimension().location().toString());
        }

        if (results.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("found", false);
            result.addProperty("message", ToolMessages.text("memory.no_match", category));
            reply.accept(result.toString());
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("found", true);
        result.addProperty("count", results.size());

        JsonArray arr = new JsonArray();
        int limit = Math.min(results.size(), 10); // cap to prevent prompt bloat
        for (int i = 0; i < limit; i++) {
            var poi = results.get(i);
            JsonObject p = new JsonObject();
            p.addProperty("category", poi.category());
            p.addProperty("label", poi.label());
            p.addProperty("x", poi.x());
            p.addProperty("y", poi.y());
            p.addProperty("z", poi.z());
            p.addProperty("dimension", poi.dimension());
            arr.add(p);
        }
        result.add("locations", arr);
        result.addProperty("hint",
                ToolMessages.text("memory.navigate"));
        reply.accept(result.toString());
    }
}
