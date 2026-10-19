package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.EatItemTask;
import com.mineagent.tools.inventory.EatItemTool.EatItemTaskRecord;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorEatingLanguageTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void eatingSnapshotAndFallbacksUseTheSelectedLanguage() {
        var task = new EatItemTask(null, new EatItemTaskRecord("call", null));
        assertEquals("Selecting food", task.snapshot().summary());
        RuntimeLanguage.configure("pt_br");
        assertEquals("Selecionando alimento", task.snapshot().summary());
        assertEquals("selecting_food", task.snapshot().stage());
        assertEquals("A alimentação falhou", task.completionMessage(TaskState.FAILED, false));
        assertEquals("timeout: Tempo de alimentação esgotado para null", task.completionMessage(TaskState.FAILED, true));
    }

    @Test void selectedFoodAndRawFailureAreNotReinterpretedAsTranslationKeys() throws Exception {
        var task = new EatItemTask(null, new EatItemTaskRecord("call", "mod:food"));
        var id = EatItemTask.class.getDeclaredField("foodId");
        id.setAccessible(true);
        id.set(task, "mod:food");
        RuntimeLanguage.configure("pt_br");
        assertEquals("Comendo mod:food", task.snapshot().summary());
        assertEquals("food=mod:food", task.snapshot().evidence());
        assertEquals("Comeu uma unidade de mod:food", task.completionMessage(TaskState.SUCCESS, false));
        String raw = "external failure {0}: Eating failed $1";
        var failure = EatItemTask.class.getDeclaredField("failReason");
        failure.setAccessible(true);
        failure.set(task, raw);
        assertEquals(TaskState.FAILED, task.tick());
        assertEquals(raw, task.completionMessage(TaskState.FAILED, false));
        assertEquals(raw, task.snapshot().blockedReason());
    }
}
