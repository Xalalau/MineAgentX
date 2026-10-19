package com.mineagent.tools.combat;

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
 * Attack an entity with a melee weapon. The companion will move within
 * range and repeatedly strike until the target is dead or the task
 * is cancelled.
 *
 * <p>This is an <b>async</b> tool - it dispatches a MeleeAttackTaskRecord
 * and returns a task_id immediately.
 */
public class MeleeAttackTool implements Tool {

    @Override
    public String name() { return "melee_attack"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return SafetyMessages.text("melee.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("entity_id", SafetyMessages.text("param.entity.attack"), 0, Integer.MAX_VALUE)
                .optionalInteger("hold_ticks", SafetyMessages.text("param.hold"), 0, 40)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
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
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var target = sp.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive() || target == sp || !target.isAttackable()) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.attack_target", entityId)));
            return;
        }

        if (!CombatSafety.canAttack(sp, target)) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.combatDenied()));
            return;
        }

        var record = new MeleeAttackTaskRecord(toolCallId, entityId, holdTicks);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for melee attacks. */
    public static class MeleeAttackTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final int entityId;
        public final int holdTicks;

        public MeleeAttackTaskRecord(String toolCallId, int entityId, int holdTicks) {
            super(toolCallId);
            this.entityId = entityId;
            this.holdTicks = holdTicks;
        }
    }
}
