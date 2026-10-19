package com.mineagent.tools.block;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.act.BlockTargeting;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.engine.world.WorldAssetObserver;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

/** Inspects a loaded container that is within normal player reach. */
public class InspectBlockStorageTool implements Tool {

    @Override public String name() { return "inspect_block_storage"; }

    @Override
    public String description() {
        return ToolMessages.text("inspect_block_storage.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", ToolMessages.text("inspect_block_storage.parameter.x"), -30_000_000, 30_000_000)
                .integer("y", ToolMessages.text("inspect_block_storage.parameter.y"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", ToolMessages.text("inspect_block_storage.parameter.z"), -30_000_000, 30_000_000)
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

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.container_position_is_outside_the_loaded_world")));
            return;
        }
        if (BlockTargeting.findVisibleHit(sp, pos,
                BlockTargeting.interactionReach(sp)).isEmpty()) {
            // Distance alone is not a valid interaction predicate: a wall can
            // hide a chest that is only one block away. Use the same sampled
            // outline ray test as placement/use/breaking so inspection cannot
            // become an x-ray read path and the model receives honest
            // visibility evidence.
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.container_is_outside_interaction_range_or_is_blocked_from_view")));
            return;
        }

        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.block_at_0_is_not_an_item_container", pos.toShortString())));
            return;
        }

        JsonObject result = new JsonObject();
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString();
        result.addProperty("block_id", blockId);
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        JsonArray slots = new JsonArray();
        var observations = new ArrayList<WorldAssetIndex.ItemObservation>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            var stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            observations.add(WorldAssetObserver.item(i, stack));
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString());
            slot.addProperty("count", stack.getCount());
            if (stack.isDamageableItem()) {
                slot.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
                slot.addProperty("max_durability", stack.getMaxDamage());
            }
            if (stack.isEnchanted()) slot.addProperty("enchanted", true);
            slots.add(slot);
        }
        result.add("slots", slots);
        result.addProperty("total_slots", container.getContainerSize());
        String containerId = level.dimension().location() + ":" + pos.toShortString();
        result.addProperty("container_id", containerId);

        var loop = TaskContext.agentLoop(player);
        if (loop != null) {
            // This is a real, in-reach block inspection, so its coordinates
            // are durable evidence. Re-observing an empty container clears
            // previously remembered contents instead of leaving phantom gear.
            loop.worldAssetIndex().observeContainer(containerId,
                    new WorldAssetIndex.Position(level.dimension().location().toString(),
                            x, y, z), observations, true, level.getGameTime());
        }
        reply.accept(result.toString());
    }
}
