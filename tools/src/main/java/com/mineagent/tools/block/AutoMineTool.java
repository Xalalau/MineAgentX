package com.mineagent.tools.block;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Mine blocks by type and quantity. The companion will navigate to and break
 * blocks matching the specified type until the desired count is collected or
 * no more matching blocks are reachable.
 *
 * <p>This is an <b>async</b> tool - it dispatches a MineBlockTaskRecord
 * and returns a task_id immediately.
 */
public class AutoMineTool implements Tool {

    @Override
    public String name() { return "auto_mine"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("auto_mine.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("block_type", ToolMessages.text("auto_mine.parameter.block_type"))
                .integer("count", ToolMessages.text("auto_mine.parameter.count"), 1, 64)
                .optionalInteger("radius", ToolMessages.text("auto_mine.parameter.radius"), 1, 32)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String blockType = ToolArgs.getString(args, "block_type");
        if (blockType == null) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_block_type")));
            return;
        }
        if (!ToolArgs.has(args, "count")) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_count")));
            return;
        }
        boolean tagQuery = blockType.startsWith("#");
        ResourceLocation blockId = ResourceLocation.tryParse(
                tagQuery ? blockType.substring(1) : blockType);
        if (blockId == null || (!tagQuery && !BuiltInRegistries.BLOCK.containsKey(blockId))) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_block_or_invalid_block_tag_0", blockType)));
            return;
        }
        if (tagQuery) {
            TagKey<net.minecraft.world.level.block.Block> tag =
                    TagKey.create(Registries.BLOCK, blockId);
            // An empty tag can never produce a target. Checking it here avoids
            // launching an expensive radius scan that is guaranteed to fail.
            if (BuiltInRegistries.BLOCK.getTag(tag).isEmpty()) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_or_empty_block_tag_0", blockId)));
                return;
            }
            blockType = "#" + blockId;
        } else {
            blockType = blockId.toString();
        }

        Integer count = ToolArgs.getIntOrNull(args, "count");
        if (count == null || count < 1 || count > 64) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.count_must_be_an_integer_from_1_to_64")));
            return;
        }
        Integer radius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : 16;
        if (radius == null || radius < 1 || radius > 32) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.radius_must_be_an_integer_from_1_to_32")));
            return;
        }

        var record = new MineBlockTaskRecord(toolCallId, blockType, count, radius);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for auto-mining. */
    public static class MineBlockTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String blockType;
        public final int count;
        public final int radius;

        public MineBlockTaskRecord(String toolCallId, String blockType, int count, int radius) {
            super(toolCallId);
            this.blockType = blockType;
            this.count = count;
            this.radius = radius;
        }
    }
}
