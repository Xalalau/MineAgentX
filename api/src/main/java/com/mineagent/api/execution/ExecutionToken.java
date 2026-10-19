package com.mineagent.api.execution;

import java.util.function.BooleanSupplier;

/** Execution-local revocation check. A batch already in vanilla code finishes atomically. */
public final class ExecutionToken {
    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();
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

    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
}
