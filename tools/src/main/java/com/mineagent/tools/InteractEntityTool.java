package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.safety.SafetyMessages;
import com.mineagent.engine.safety.CombatSafety;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Interact with an entity. Supports use (right-click) and attack
 * (left-click) interactions with entities by entity ID.
 *
 * <p>This is an <b>async</b> tool - it dispatches an InteractEntityTaskRecord
 * and returns a task_id immediately.
 */
public class InteractEntityTool implements Tool {

    @Override
    public String name() { return "interact_entity"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return SafetyMessages.text("entity.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("button", SafetyMessages.text("param.button"))
                .integer("entity_id", SafetyMessages.text("param.entity.use"), 0, Integer.MAX_VALUE)
                .optionalInteger("hold_ticks", SafetyMessages.text("param.entity.hold"), 0, 40)
                .optionalString("item_id", SafetyMessages.text("param.item"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String button = ToolArgs.getString(args, "button");
        if (button == null) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.missing", "button")));
            return;
        }
        if (!ToolArgs.has(args, "entity_id")) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.missing", "entity_id")));
            return;
        }
        Integer entityId = ToolArgs.getIntOrNull(args, "entity_id");
        if (entityId == null || entityId < 0) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.entity_id")));
            return;
        }
        Integer holdTicks = ToolArgs.has(args, "hold_ticks")
                ? ToolArgs.getIntOrNull(args, "hold_ticks") : 0;
        if (holdTicks == null || holdTicks < 0 || holdTicks > 40) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.hold")));
            return;
        }
        String itemId = ToolArgs.getString(args, "item_id");

        // Validate button
        if (!java.util.Set.of("use", "attack", "use_offhand").contains(button)) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.button", button)));
            return;
        }

        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var target = sp.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive() || target == sp) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.use_target", entityId)));
            return;
        }
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.item", itemId)));
                return;
            }
            itemId = id.toString();
        }

        if ("attack".equals(button) && !CombatSafety.canAttack(sp, target)) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.combatDenied()));
            return;
        }

        var record = new InteractEntityTaskRecord(toolCallId, button, entityId, holdTicks, itemId);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for entity interaction. */
    public static class InteractEntityTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String button;
        public final int entityId;
        public final int holdTicks;
        public final String itemId;

        public InteractEntityTaskRecord(String toolCallId, String button, int entityId,
                                         int holdTicks, String itemId) {
            super(toolCallId);
            this.button = button;
            this.entityId = entityId;
            this.holdTicks = holdTicks;
            this.itemId = itemId;
        }
    }
}
