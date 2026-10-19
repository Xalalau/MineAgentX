package com.mineagent.engine.safety;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Server-authenticated origin, independent of live shooter lookup and combat permissions. */
public final class ProjectileProvenance {
    private static final String COMPANION_ORIGIN = "mineagent:companion_projectile_origin";

    private ProjectileProvenance() {}

    public static void capture(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel
                && event.getEntity() instanceof Projectile projectile
                && projectile.getOwner() instanceof ServerPlayer shooter
                && CombatSafety.isCompanion(shooter)) {
            // NeoForge persists this compound under Entity's NeoForgeData. Do not
            // clear it on disk-load/rejoin when the original shooter is absent.
            projectile.getPersistentData().putUUID(COMPANION_ORIGIN, shooter.getUUID());
        }
    }

    public static boolean hasCompanionOrigin(Entity direct) {
        return direct instanceof Projectile projectile
                && projectile.getPersistentData().hasUUID(COMPANION_ORIGIN);
    }
}
