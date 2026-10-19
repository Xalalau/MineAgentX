package com.mineagent.engine.safety;

import com.mineagent.api.safety.CombatPolicy;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Authoritative target classification shared by tools, tasks and damage hooks. */
public final class CombatSafety {
    private CombatSafety() {}

    public static boolean isCompanion(Entity entity) {
        return entity instanceof Player player
                && ((player instanceof ServerPlayer serverPlayer
                    && serverPlayer.gameMode instanceof FakePlayerGameMode)
                    || MineAgentEngine.isCompanionPlayer(player.getUUID()));
    }

    /** Companion bodies are players too: classify them before ordinary players. */
    public static boolean permitsTarget(Entity target) {
        if (target == null) return false;
        var kind = isCompanion(target) ? CombatPolicy.TargetKind.COMPANION
                : target instanceof Player ? CombatPolicy.TargetKind.PLAYER
                : CombatPolicy.TargetKind.OTHER;
        return CombatPolicy.current().allows(kind);
    }

    /** Policy never overrides vanilla PvP, team, game mode or dimension restrictions. */
    public static boolean canAttack(ServerPlayer attacker, Entity target) {
        if (attacker == null || target == null || attacker == target
                || !attacker.isAlive() || attacker.isSpectator()
                || !target.isAlive() || !target.isAttackable()
                || target.level() != attacker.level() || !permitsTarget(target)) return false;
        if (target instanceof Player player) {
            return !player.isSpectator() && attacker.canHarmPlayer(player)
                    && attacker.getServer() != null && attacker.getServer().isPvpAllowed();
        }
        return true;
    }

    /** Match NeoForge's actual sweep volume/range before knockback is applied. */
    public static boolean hasProtectedSweepBystander(ServerPlayer attacker, Entity target) {
        if (attacker == null || target == null) return false;
        double reach = attacker.entityInteractionRange();
        for (Entity bystander : attacker.level().getEntities(attacker,
                target.getBoundingBox().inflate(1.0, 0.25, 1.0),
                entity -> entity instanceof Player && entity != target && entity.isAlive())) {
            if (!attacker.isAlliedTo(bystander)
                    && attacker.distanceToSqr(bystander) < reach * reach
                    && !permitsTarget(bystander)) return true;
        }
        return false;
    }

    /**
     * Refuse blind shots and a visible protected body crossing the firing lane.
     * Ballistic spread/lead can still differ from this conservative lane check;
     * the incoming-damage event independently protects every actual victim.
     */
    public static boolean canFireAt(ServerPlayer attacker, Entity target) {
        if (!canAttack(attacker, target) || !attacker.hasLineOfSight(target)) return false;
        var from = attacker.getEyePosition();
        var to = target.position().add(0, target.getBbHeight() * 0.75, 0);
        var lane = new AABB(from, to).inflate(0.5);
        for (Entity bystander : attacker.level().getEntities(attacker, lane,
                entity -> entity instanceof Player && entity != target && !permitsTarget(entity))) {
            if (bystander.getBoundingBox().inflate(0.5).clip(from, to).isPresent()
                    || bystander.getBoundingBox().inflate(0.5).contains(from)) return false;
        }
        return true;
    }
}
