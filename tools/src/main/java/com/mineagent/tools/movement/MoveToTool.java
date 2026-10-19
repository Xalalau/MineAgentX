package com.mineagent.tools.movement;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Move the companion to a target position using A* pathfinding.
 * Supports 4 goal modes: xz (horizontal), xzy (exact block),
 * y (altitude), block (adjacent to a block).
 *
 * <p>This is an <b>async</b> tool - it dispatches a MoveToTaskRecord
 * and returns a task_id immediately.
 */
public class MoveToTool implements Tool {

    @Override
    public String name() { return "goto"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("goto.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("goal_mode", ToolMessages.text("goto.parameter.goal_mode"))
                .optionalInteger("x", ToolMessages.text("goto.parameter.x"), -30_000_000, 30_000_000)
                .optionalInteger("y", ToolMessages.text("goto.parameter.y"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", ToolMessages.text("goto.parameter.z"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String goalMode = ToolArgs.getString(args, "goal_mode");
        if (goalMode == null) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_goal_mode_use_xz_xzy_y_block")));
            return;
        }
        goalMode = goalMode.trim().toLowerCase(java.util.Locale.ROOT);
        Integer parsedX = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");

        // Validate goal mode
        if (!java.util.Set.of("xz", "xzy", "y", "block").contains(goalMode)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_goal_mode_0_use_xz_xzy_y_block", goalMode)));
            return;
        }

        // Validate required coordinates per goal mode
        switch (goalMode) {
            case "xz" -> {
                if (parsedX == null || z == null) {
                    reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.goal_mode_xz_requires_both_x_and_z_coordinates")));
                    return;
                }
            }
            case "xzy", "block" -> {
                if (parsedX == null || y == null || z == null) {
                    reply.accept(ToolArgs.errorJson(ToolMessages.text("error.goal_mode_0_requires_x_y_and_z_coordinates", goalMode)));
                    return;
                }
            }
            case "y" -> {
                if (y == null) {
                    reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.goal_mode_y_requires_a_y_coordinate")));
                    return;
                }
            }
        }

        int x = parsedX != null ? parsedX : (int) Math.floor(player.posX());
        var target = new net.minecraft.core.BlockPos(x,
                y != null ? y : (int) Math.floor(player.posY()),
                z != null ? z : (int) Math.floor(player.posZ()));
        var level = ((com.mineagent.engine.entity.CompanionEntity) player)
                .serverPlayer().serverLevel();
        if (!level.isInWorldBounds(target)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.target_is_outside_this_dimension_s_world_bounds_0", target.toShortString())));
            return;
        }

        // Create task record (would be a concrete MoveToTaskRecord)
        // For now, dispatch via TaskDispatch
        var record = new MoveToTaskRecord(toolCallId, goalMode, x, y, z);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for movement. */
    public static class MoveToTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String goalMode;
        public final int x;
        public final Integer y;
        public final Integer z;

        public MoveToTaskRecord(String toolCallId, String goalMode, int x, Integer y, Integer z) {
            super(toolCallId);
            this.goalMode = goalMode;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
