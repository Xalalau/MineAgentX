package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.safety.SafetyMessages;
import com.mineagent.engine.safety.CombatSafety;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.combat.MeleeAttackTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import java.util.Locale;

/**
 * Executes a melee attack task — tracks the target entity, navigates
 * within melee range (3 blocks), and strikes repeatedly until the
 * target dies or the task is cancelled.
 */
public class MeleeAttackTask extends CompanionTask<MeleeAttackTool.MeleeAttackTaskRecord>
        implements IntentAwareTask {

    private enum Phase { NAVIGATE, ATTACK, DONE }

    /** Maximum melee attack range in blocks. */
    private static final double MELEE_RANGE = 3.0;
    /** Minimum companion health to keep fighting. */
    private static final float MIN_HEALTH = 5.0f;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private int aimTicks;
    private int repathTicks;
    private net.minecraft.core.BlockPos lastNavTarget;
    private String failReason;
    private int strikesLanded;
    /** True only after the requested target passed the start-time checks. */
    private boolean startedSuccessfully;

    public MeleeAttackTask(AgentPlayer player, MeleeAttackTool.MeleeAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        aimTicks = 0;
        repathTicks = 0;
        lastNavTarget = null;
        failReason = null;
        strikesLanded = 0;
        startedSuccessfully = false;

        // Resolve target entity
        ServerLevel level = TaskContext.serverPlayer(player).serverLevel();
        target = level.getEntity(record.entityId);
        if (target == null || !target.isAlive()) {
            failReason = SafetyMessages.text("target.missing", record.entityId);
            phase = Phase.DONE;
            return;
        }
        if (target.level() != TaskContext.serverPlayer(player).level()) {
            failReason = SafetyMessages.text("target.dimension");
            phase = Phase.DONE;
            return;
        }

        if (!CombatSafety.canAttack(TaskContext.serverPlayer(player), target)) {
            failReason = SafetyMessages.combatDenied();
            phase = Phase.DONE;
            return;
        }

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                // Reached target area, switch to attack
                if (phase == Phase.NAVIGATE) phase = Phase.ATTACK;
            }

            @Override
            public void onNavigationFailed(String reason) {
                failReason = SafetyMessages.text("nav.target", reason);
                phase = Phase.DONE;
            }
        });

        navigateToTarget();
        startedSuccessfully = true;
    }

    @Override
    protected void onResume() {
        int verifiedStrikes = strikesLanded;
        // A target may die in the same server tick as a successful swing and
        // before the scheduler publishes the terminal task state. Re-running
        // onStart would treat the removed entity as an invalid request and
        // incorrectly report failure instead of preserving the kill result.
        if (target != null && !target.isAlive()) {
            strikesLanded = verifiedStrikes;
            phase = Phase.DONE;
            failReason = null;
            startedSuccessfully = true;
            if (nav != null) nav.cancel();
            TaskContext.inputDriver(player).clear();
            return;
        }
        onStart();
        strikesLanded = verifiedStrikes;
    }

    @Override
    protected TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        // Check companion health — retreat if dying
        if (player.health() < MIN_HEALTH && player.isAlive()) {
            failReason = SafetyMessages.text("companion.critical_health",
                    String.format(Locale.ROOT, "%.1f", player.health()));
            phase = Phase.DONE;
        }

        // Check if companion died
        if (!player.isAlive()) {
            failReason = SafetyMessages.text("companion.dead");
            phase = Phase.DONE;
        }

        // A target that was invalid at task creation is a failed request, not
        // a successful kill. Only a task that actually passed its start-time
        // validation may use target disappearance as the kill postcondition.
        if (!startedSuccessfully) {
            cancelNav();
            return TaskState.FAILED;
        }

        // Check if target is gone
        if (target == null || !target.isAlive()) {
            // Target died — success
            cancelNav();
            return TaskState.SUCCESS;
        }

        if (!CombatSafety.canAttack(TaskContext.serverPlayer(player), target)) {
            failReason = SafetyMessages.combatDenied();
            phase = Phase.DONE;
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case NAVIGATE -> tickNavigate();
            case ATTACK -> tickAttack();
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return TaskState.RUNNING;
    }

    private void tickNavigate() {
        // Re-navigate to target's current position periodically
        nav.tick();
        repathTicks++;

        // Check if we're already in range
        double dist = distanceToTarget();
        if (dist <= MELEE_RANGE) {
            nav.cancel();
            TaskContext.inputDriver(player).clear();
            phase = Phase.ATTACK;
            aimTicks = 0;
        } else if (repathTicks >= 20
                && (lastNavTarget == null
                    || lastNavTarget.distManhattan(target.blockPosition()) >= 2)) {
            navigateToTarget();
        }
    }

    private void tickAttack() {
        // Check distance — if target moved away, re-navigate
        double dist = distanceToTarget();
        if (dist > MELEE_RANGE + 1.0) {
            navigateToTarget();
            phase = Phase.NAVIGATE;
            return;
        }

        // Look at target
        lookAtTarget();

        if (!TaskContext.serverPlayer(player).hasLineOfSight(target)) {
            navigateToTarget();
            return;
        }

        // hold_ticks now has explicit semantics: extra stable aim time before
        // each swing. Java melee has no continuous "held attack" packet.
        if (aimTicks++ < record.holdTicks) return;

        var sp = TaskContext.serverPlayer(player);
        if (sp.getAttackStrengthScale(0.5f) >= 0.9f) {
            if (!Interaction.attackEntity(sp, target)) {
                navigateToTarget();
                return;
            }
            strikesLanded++;
            aimTicks = 0;
        }
    }

    private void navigateToTarget() {
        if (target == null || !target.isAlive()) return;
        nav.navigateNear(
                target.blockPosition().getX(),
                target.blockPosition().getY(),
                target.blockPosition().getZ(),
                2 // arrive within 2 blocks
        );
        lastNavTarget = target.blockPosition().immutable();
        repathTicks = 0;
        phase = Phase.NAVIGATE;
    }

    private double distanceToTarget() {
        if (target == null) return Double.MAX_VALUE;
        var companionPos = TaskContext.serverPlayer(player).position();
        var targetPos = target.position();
        return companionPos.distanceTo(targetPos);
    }

    private void lookAtTarget() {
        if (target == null) return;
        var sp = TaskContext.serverPlayer(player);
        var dir = target.position().subtract(sp.getEyePosition()).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(Math.asin(-dir.y));
        sp.setYRot(yaw);
        sp.setXRot(pitch);
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        cancelNav();
    }

    @Override
    public TaskSnapshot snapshot() {
        var pos = target == null ? null : target.blockPosition();
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        return TaskSnapshot.progress(stage,
                SafetyMessages.text("melee.progress", record.entityId),
                strikesLanded, -1,
                pos == null ? null : pos.getX(), pos == null ? null : pos.getY(),
                pos == null ? null : pos.getZ(),
                phase == Phase.DONE ? failReason : null,
                target == null ? null : "target_alive=" + target.isAlive(),
                ((long) strikesLanded << 3) ^ (phase == null ? 0L : phase.ordinal()));
    }

    @Override
    public IntentContract intentContract() {
        var policy = new IntentContract.TerrainPolicy(false, false, false, true,
                0, 0, 4, IntentContract.CleanupMode.CONTEXTUAL);
        return new IntentContract(SafetyMessages.text("intent.melee", record.entityId),
                SafetyMessages.text("intent.defeated"), null, null, null, policy,
                java.util.List.of(new IntentContract.Constraint("combat_terrain",
                        IntentContract.ConstraintKind.HARD,
                        SafetyMessages.text("constraint.melee_terrain"),
                        "navigation")));
    }

    @Override
    protected String successMessage() {
        return SafetyMessages.text("melee.success", record.entityId);
    }

    @Override
    protected String timeoutMessage() {
        return SafetyMessages.text("melee.timeout", record.entityId);
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return SafetyMessages.text("melee.failure", record.entityId);
    }
}
