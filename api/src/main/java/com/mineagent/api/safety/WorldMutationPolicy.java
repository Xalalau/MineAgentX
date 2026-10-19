package com.mineagent.api.safety;

/**
 * A navigation/recovery goal is not permission to dismantle existing blocks.
 * Unknown provenance must fail closed, including same-type replaced supports.
 */
public final class WorldMutationPolicy {
    public enum BreakCause { EXPLICIT_TASK, NAVIGATION, RECOVERY, TEMPORARY_CLEANUP }

    private static final ThreadLocal<BreakCause> BREAK_CAUSE =
            ThreadLocal.withInitial(() -> BreakCause.EXPLICIT_TASK);

    private WorldMutationPolicy() {}

    /** The task-owned default preserves mining; physical controllers narrow authority. */
    public static BreakCause currentBreakCause() { return BREAK_CAUSE.get(); }

    /** Scope inherited legacy calls on this thread; nested calls/exceptions cannot leak authority. */
    public static void runWithBreakCause(BreakCause cause, Runnable action) {
        BreakCause previous = BREAK_CAUSE.get();
        BREAK_CAUSE.set(cause);
        try {
            action.run();
        } finally {
            BREAK_CAUSE.set(previous);
        }
    }

    public static boolean allowsBreaking(BreakCause cause) {
        return cause == BreakCause.EXPLICIT_TASK;
    }
}
