package com.mineagent.tools.block;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Reads one already-loaded block without forcing chunk generation. */
public class InspectBlockTool implements Tool {

    @Override public String name() { return "inspect_block"; }

    @Override
    public String description() {
        return ToolMessages.text("inspect_block.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", ToolMessages.text("inspect_block.parameter.x"), -30_000_000, 30_000_000)
                .integer("y", ToolMessages.text("inspect_block.parameter.y"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", ToolMessages.text("inspect_block.parameter.z"), -30_000_000, 30_000_000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.parameters_x_y_and_z_must_be_exact_integers")));
            return;
        }

        var level = ((CompanionEntity) player).serverPlayer().level();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            // getBlockState on an arbitrary coordinate can synchronously load
            // or generate a chunk. Perception must never stall the server that way.
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.block_position_is_outside_the_loaded_world")));
            return;
        }

        var state = level.getBlockState(pos);
        JsonObject result = new JsonObject();
        result.addProperty("block_id", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (var property : state.getProperties()) {
                properties.addProperty(property.getName(),
                        state.getValue(property).toString());
            }
            result.add("properties", properties);
        }

        var blockEntity = level.getBlockEntity(pos);
        result.addProperty("has_block_entity", blockEntity != null);
        if (blockEntity != null) {
            var tag = blockEntity.saveWithoutMetadata(level.registryAccess());
            if (!tag.isEmpty()) result.addProperty("block_entity_data", tag.toString());
        }
        result.addProperty("is_air", state.isAir());
        result.addProperty("is_solid", state.isSolidRender(level, pos));
        result.addProperty("hardness", state.getDestroySpeed(level, pos));
        reply.accept(result.toString());
    }
}
