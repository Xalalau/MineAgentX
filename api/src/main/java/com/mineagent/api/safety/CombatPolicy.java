package com.mineagent.api.safety;

/** Server-owned combat permissions; companion permission is independent of PvP. */
public record CombatPolicy(boolean allowAttackingPlayers, boolean allowAttackingCompanions) {
    public enum TargetKind { PLAYER, COMPANION, OTHER }

    public static final CombatPolicy DEFAULTS = new CombatPolicy(false, false);
    private static volatile CombatPolicy current = DEFAULTS;

    /** Call on server initialization and configuration reload. Null fails closed. */
    public static void configure(CombatPolicy policy) {
        current = policy == null ? DEFAULTS : policy;
    }

    /** Read at execution time, never cache this snapshot in a queued task. */
    public static CombatPolicy current() {
        return current;
    }

    public boolean allows(TargetKind target) {
        if (target == null) return false;
        return switch (target) {
            case PLAYER -> allowAttackingPlayers;
            case COMPANION -> allowAttackingCompanions;
            case OTHER -> true;
        };
    }
}
