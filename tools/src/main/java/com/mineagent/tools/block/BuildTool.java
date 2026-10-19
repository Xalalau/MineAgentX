package com.mineagent.tools.block;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Place or clear blocks in a 1-512 block range. The companion will navigate
 * to each position and place/remove blocks sequentially.
 *
 * <p>This is an <b>async</b> tool — it dispatches a BuildTaskRecord
 * and returns a task_id immediately.
 */
public class BuildTool implements Tool {

    @Override
    public String name() { return "build"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("build.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", ToolMessages.text("build.parameter.mode"))
                .optionalString("block_type", ToolMessages.text("build.parameter.block_type"))
                .array("positions", ToolMessages.text("build.parameter.positions"),
                        Map.of("type", "array", "items", Map.of("type", "integer"),
                                "minItems", 3, "maxItems", 3), 1)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String mode = ToolArgs.getString(args, "mode");
        if (mode == null || mode.isBlank()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_mode")));
            return;
        }
        mode = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("place") && !mode.equals("clear")) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_mode_0_use_place_or_clear", mode)));
            return;
        }

        String blockType = ToolArgs.getString(args, "block_type");
        if (mode.equals("place")) {
            var blockId = blockType == null ? null
                    : net.minecraft.resources.ResourceLocation.tryParse(blockType.trim());
            if (blockId == null
                    || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(blockId)) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_block_type_0", String.valueOf(blockType))));
                return;
            }
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId);
            if (block.asItem() == net.minecraft.world.item.Items.AIR) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.block_type_has_no_placeable_inventory_item_0", blockId)));
                return;
            }
            blockType = blockId.toString();
        } else {
            blockType = "minecraft:air";
        }
        JsonArray positionsArray = ToolArgs.getArray(args, "positions");
        if (positionsArray == null || positionsArray.isEmpty()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.positions_must_be_a_non_empty_array")));
            return;
        }
        if (positionsArray.size() > 512) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.too_many_positions_max_512")));
            return;
        }

        int[][] positions = new int[positionsArray.size()][3];
        try {
            for (int i = 0; i < positionsArray.size(); i++) {
                var element = positionsArray.get(i);
                if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
                    throw new IllegalArgumentException("coordinate must contain exactly three integers");
                }
                var coord = element.getAsJsonArray();
                positions[i][0] = exactInt(coord.get(0));
                positions[i][1] = exactInt(coord.get(1));
                positions[i][2] = exactInt(coord.get(2));
                var level = ((com.mineagent.engine.entity.CompanionEntity) player)
                        .serverPlayer().serverLevel();
                var pos = new net.minecraft.core.BlockPos(
                        positions[i][0], positions[i][1], positions[i][2]);
                if (positions[i][1] < level.getMinBuildHeight()
                        || positions[i][1] >= level.getMaxBuildHeight()
                        || !level.getWorldBorder().isWithinBounds(pos)) {
                    throw new IllegalArgumentException("coordinate is outside the buildable world");
                }
            }
        } catch (Exception e) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.each_position_must_be_exactly_x_y_z_with_integer_coordinates_inside_the_world_border")));
            return;
        }

        var record = new BuildTaskRecord(toolCallId, mode, blockType, positions);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    private static int exactInt(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("not a number");
        }
        try {
            return new java.math.BigDecimal(value.getAsString().trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException("not an exact integer", error);
        }
    }

    /** Task record for building. */
    public static class BuildTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String mode;
        public final String blockType;
        public final int[][] positions;

        public BuildTaskRecord(String toolCallId, String mode, String blockType, int[][] positions) {
            super(toolCallId);
            this.mode = mode;
            this.blockType = blockType;
            this.positions = positions;
        }
    }
}
