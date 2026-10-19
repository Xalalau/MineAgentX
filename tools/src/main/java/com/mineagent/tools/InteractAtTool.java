package com.mineagent.tools;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Interact with a block at a specific world position. Supports
 * use (right-click), attack (left-click), and hold interactions.
 *
 * <p>This is an <b>async</b> tool - it dispatches an InteractAtTaskRecord
 * and returns a task_id immediately.
 */
public class InteractAtTool implements Tool {

    @Override
    public String name() { return "interact_at"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("interact_at.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("button", ToolMessages.text("interact_at.parameter.button"))
                .integer("x", ToolMessages.text("interact_at.parameter.x"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("y", ToolMessages.text("interact_at.parameter.y"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", ToolMessages.text("interact_at.parameter.z"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("hold_ticks", ToolMessages.text("interact_at.parameter.hold_ticks"), 0, 40)
                .optionalString("item_id", ToolMessages.text("interact_at.parameter.item_id"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String button = ToolArgs.getString(args, "button");
        if (button == null) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_button")));
            return;
        }
        if (!ToolArgs.has(args, "x") || !ToolArgs.has(args, "y") || !ToolArgs.has(args, "z")) {
            reply.accept(com.mineagent.api.agent.tool.ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameters_x_y_z")));
            return;
        }
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.parameters_x_y_and_z_must_be_exact_integers")));
            return;
        }
        Integer holdTicks = ToolArgs.has(args, "hold_ticks")
                ? ToolArgs.getIntOrNull(args, "hold_ticks") : 0;
        if (holdTicks == null || holdTicks < 0 || holdTicks > 40) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.hold_ticks_must_be_an_integer_from_0_to_40")));
            return;
        }
        String itemId = ToolArgs.getString(args, "item_id");

        // Validate button
        if (!java.util.Set.of("use", "attack", "use_offhand").contains(button)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_button_0_use_use_attack_or_use_offhand", button)));
            return;
        }

        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var target = new net.minecraft.core.BlockPos(x, y, z);
        if (!sp.level().isInWorldBounds(target)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.target_block_is_outside_this_dimension_s_world_bounds")));
            return;
        }
        if (!sp.level().isLoaded(target)) {
            // getBlockState can synchronously create/load a chunk. Tool calls
            // run on the server thread, so allowing an unloaded coordinate
            // here can stall the tick loop (and make a later interaction use
            // stale navigation assumptions). Require the caller to navigate
            // into a loaded area first, matching vanilla's local interaction
            // semantics.
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.target_block_is_in_an_unloaded_chunk_move_closer_before_interacting")));
            return;
        }
        if (sp.level().getBlockState(target).isAir()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.target_block_is_air_at_0", target.toShortString())));
            return;
        }
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_item", itemId)));
                return;
            }
            itemId = id.toString();
        }

        var record = new InteractAtTaskRecord(toolCallId, button, x, y, z, holdTicks, itemId);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for block interaction. */
    public static class InteractAtTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String button;
        public final int x;
        public final int y;
        public final int z;
        public final int holdTicks;
        public final String itemId;

        public InteractAtTaskRecord(String toolCallId, String button, int x, int y, int z,
                                     int holdTicks, String itemId) {
            super(toolCallId);
            this.button = button;
            this.x = x;
            this.y = y;
            this.z = z;
            this.holdTicks = holdTicks;
            this.itemId = itemId;
        }
    }
}
