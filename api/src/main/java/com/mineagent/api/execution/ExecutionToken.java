package com.mineagent.api.execution;

import java.util.function.BooleanSupplier;

/** Execution-local revocation check. A batch already in vanilla code finishes atomically. */
public final class ExecutionToken {
    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<BooleanSupplier> RETAINED = new ThreadLocal<>();
    private static final BooleanSupplier NO_RETAINED_AUTHORITY = () -> false;
    private ExecutionToken() { }

    public static boolean active() {
        BooleanSupplier token = CURRENT.get();
        return token == null || token.getAsBoolean();
    }

    public static Scope bind(BooleanSupplier token) {
        BooleanSupplier previous = CURRENT.get();
        CURRENT.set(token);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    /** Bind exact admitted work authority independently of invocation/ack liveness. */
    public static Scope bindRetainedAuthority(BooleanSupplier authority) {
        java.util.Objects.requireNonNull(authority, "authority");
        BooleanSupplier previous = RETAINED.get();
        RETAINED.set(authority);
        return () -> {
            if (previous == null) RETAINED.remove(); else RETAINED.set(previous);
        };
    }

    /** Capture now for queued work; an unbound caller receives no retained authority. */
    public static BooleanSupplier captureRetainedAuthority() {
        BooleanSupplier authority = RETAINED.get();
        return authority == null ? NO_RETAINED_AUTHORITY : authority;
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
}
