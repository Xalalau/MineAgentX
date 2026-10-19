package com.mineagent.safety;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatPolicyTest {
    @Test void serverOptInsAreIndependentAndReloadIsImmediatelyVisible() throws Exception {
        Class<?> policy = Class.forName("com.mineagent.api.safety.CombatPolicy");
        java.lang.reflect.Method configure;
        try {
            configure = policy.getMethod("configure", policy);
        } catch (NoSuchMethodException missing) {
            fail("Server init/reload configuration hook is missing", missing);
            return;
        }
        var current = policy.getMethod("current");
        Class<?> kind = Class.forName("com.mineagent.api.safety.CombatPolicy$TargetKind");
        var allows = policy.getMethod("allows", kind);
        try {
            for (boolean players : new boolean[]{false, true}) {
                for (boolean companions : new boolean[]{false, true}) {
                    configure.invoke(null, policy.getConstructor(boolean.class, boolean.class)
                            .newInstance(players, companions));
                    Object live = current.invoke(null);
                    for (Object value : kind.getEnumConstants()) {
                        boolean expected = switch (value.toString()) {
                            case "PLAYER" -> players;
                            case "COMPANION" -> companions;
                            default -> true;
                        };
                        assertEquals(expected, allows.invoke(live, value));
                    }
                    assertEquals(false, allows.invoke(live, new Object[]{null}));
                }
            }
            configure.invoke(null, new Object[]{null});
            assertEquals(policy.getField("DEFAULTS").get(null), current.invoke(null));
        } finally {
            configure.invoke(null, policy.getField("DEFAULTS").get(null));
        }
    }

    @Test void deniesPlayersAndCompanionsWithoutServerOptIn() throws Exception {
        Class<?> policy;
        try {
            policy = Class.forName("com.mineagent.api.safety.CombatPolicy");
        } catch (ClassNotFoundException missing) {
            fail("Safe-by-default combat policy is not implemented", missing);
            return;
        }
        Object defaults = policy.getField("DEFAULTS").get(null);
        Class<?> kind = Class.forName("com.mineagent.api.safety.CombatPolicy$TargetKind");
        var allows = policy.getMethod("allows", kind);
        for (Object value : kind.getEnumConstants()) {
            assertEquals(value.toString().equals("OTHER"), allows.invoke(defaults, value),
                    "default permission for " + value);
        }
    }
}
