package com.mineagent.safety;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.safety.SafetyMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyMessagesTest {
    @AfterEach void restoreEnglish() { RuntimeLanguage.configure("en_us"); }

    @Test void combatDenialUsesTheSharedServerLanguage() {
        RuntimeLanguage.configure("en_us");
        assertEquals("Server combat policy denies attacking this target.", SafetyMessages.combatDenied());
        RuntimeLanguage.configure("pt_br");
        assertEquals("A política de combate do servidor proíbe atacar este alvo.", SafetyMessages.combatDenied());
    }

    @Test void recoveryDiagnosticUsesTheSharedServerLanguage() {
        RuntimeLanguage.configure("en_us");
        assertEquals("Recovery will preserve existing blocks and seek another route.", SafetyMessages.recoveryPreservesWorld());
        RuntimeLanguage.configure("pt_br");
        assertEquals("A recuperação preservará os blocos existentes e buscará outra rota.", SafetyMessages.recoveryPreservesWorld());
    }

    @Test void combatDescriptionsSchemasOutcomesAndDefenseHaveAlignedTranslations() throws Exception {
        java.lang.reflect.Method text;
        try {
            text = SafetyMessages.class.getMethod("text", String.class, Object[].class);
        } catch (NoSuchMethodException missing) {
            fail("Bilingual combat tools/tasks catalog is missing", missing);
            return;
        }
        for (String key : new String[]{"melee.description", "ranged.description", "entity.description",
                "param.entity.attack", "param.hold", "param.charge", "param.button", "param.item",
                "target.dimension", "companion.dead", "weapon.invalid", "ammo.missing",
                "defense.creeper", "defense.low_health", "defense.no_threats", "defense.too_long",
                "unstuck.wiggle", "unstuck.free", "unstuck.backed_out", "unstuck.give_up",
                "breath.low_air", "breath.reached_air", "breath.submerged",
                "break.vanilla_rejected", "break.invalid", "break.air", "break.visible_face"}) {
            RuntimeLanguage.configure("en_us");
            String english = (String) text.invoke(null, key, new Object[0]);
            RuntimeLanguage.configure("pt_br");
            String portuguese = (String) text.invoke(null, key, new Object[0]);
            assertFalse(english.isBlank(), key);
            assertFalse(portuguese.isBlank(), key);
            assertNotEquals(english, portuguese, key);
        }
        RuntimeLanguage.configure("pt_br");
        assertEquals("Ataque corpo a corpo concluído contra a entidade 17.",
                text.invoke(null, "melee.success", new Object[]{17}));
        assertEquals("Ataque à distância concluído contra a entidade 17 (disparos: 2).",
                text.invoke(null, "ranged.success", new Object[]{17, 2}));
    }
}
