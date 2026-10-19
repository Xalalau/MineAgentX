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
 * Shoot a projectile at a target entity. The companion will aim at the target
 * and fire the ranged weapon (bow, crossbow, etc.). Supports charge time
 * for bows.
 *
 * <p>This is an <b>async</b> tool - it dispatches a RangedAttackTaskRecord
 * and returns a task_id immediately.
 */
public class RangedAttackTool implements Tool {

    @Override
    public String name() { return "ranged_attack"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return SafetyMessages.text("ranged.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("entity_id", SafetyMessages.text("param.entity.shoot"), 0, Integer.MAX_VALUE)
                .optionalInteger("charge_ticks", SafetyMessages.text("param.charge"), 3, 60)
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
        Integer chargeTicks = ToolArgs.has(args, "charge_ticks")
                ? ToolArgs.getIntOrNull(args, "charge_ticks") : 20;
        // A bow released before three ticks has zero vanilla shooting power,
        // so accepting 1-2 advertised a shot that could never be produced.
        if (chargeTicks == null || chargeTicks < 3 || chargeTicks > 60) {
            reply.accept(ToolArgs.errorJson(SafetyMessages.text("error.charge")));
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

        var record = new RangedAttackTaskRecord(toolCallId, entityId, chargeTicks);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for ranged attacks. */
    public static class RangedAttackTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final int entityId;
        public final int chargeTicks;

        public RangedAttackTaskRecord(String toolCallId, int entityId, int chargeTicks) {
            super(toolCallId);
            this.entityId = entityId;
            this.chargeTicks = chargeTicks;
        }
    }
}
