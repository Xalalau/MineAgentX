package com.mineagent.safety;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldMutationPolicyTest {
    @Test void navigationAuthorityPropagatesToLegacyBreakCallsWithoutLeaking() throws Exception {
        Class<?> policy = Class.forName("com.mineagent.api.safety.WorldMutationPolicy");
        Class<?> cause = Class.forName("com.mineagent.api.safety.WorldMutationPolicy$BreakCause");
        java.lang.reflect.Method run;
        java.lang.reflect.Method current;
        try {
            run = policy.getMethod("runWithBreakCause", cause, Runnable.class);
            current = policy.getMethod("currentBreakCause");
        } catch (NoSuchMethodException missing) {
            fail("Legacy movement calls need a scoped non-destructive authority", missing);
            return;
        }
        Object explicit = cause.getEnumConstants()[0];
        Object navigation = cause.getEnumConstants()[1];
        Object recovery = cause.getEnumConstants()[2];
        assertEquals(explicit, current.invoke(null));
        Runnable nested = () -> {
            try {
                assertEquals(navigation, current.invoke(null));
                assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> run.invoke(null, recovery, (Runnable) () -> { throw new IllegalStateException("test"); }));
                assertEquals(navigation, current.invoke(null), "inner exceptions must restore authority");
                java.util.concurrent.atomic.AtomicReference<Object> other = new java.util.concurrent.atomic.AtomicReference<>();
                Thread thread = new Thread(() -> {
                    try { other.set(current.invoke(null)); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                thread.start();
                thread.join();
                assertEquals(explicit, other.get(), "authority must not leak to another server/thread");
            } catch (ReflectiveOperationException | InterruptedException error) {
                throw new AssertionError(error);
            }
        };
        run.invoke(null, navigation, nested);
        assertEquals(explicit, current.invoke(null));
        run.invoke(null, null, (Runnable) () -> {
            try { assertNull(current.invoke(null)); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertEquals(explicit, current.invoke(null));
    }

    @Test void onlyExplicitTasksMayBreakUnattributedWorldBlocks() throws Exception {
        Class<?> policy;
        try {
            policy = Class.forName("com.mineagent.api.safety.WorldMutationPolicy");
        } catch (ClassNotFoundException missing) {
            fail("Non-destructive autonomous mutation policy is missing", missing);
            return;
        }
        Class<?> cause = Class.forName("com.mineagent.api.safety.WorldMutationPolicy$BreakCause");
        var allows = policy.getMethod("allowsBreaking", cause);
        for (Object value : cause.getEnumConstants()) {
            assertEquals(value.toString().equals("EXPLICIT_TASK"), allows.invoke(null, value),
                    "break authority for " + value);
        }
        assertEquals(false, allows.invoke(null, new Object[]{null}));
    }
}
