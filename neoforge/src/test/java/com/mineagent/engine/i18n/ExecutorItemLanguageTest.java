package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.UseItemTask;
import com.mineagent.tools.inventory.UseItemTool.UseItemTaskRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorItemLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void itemUseFormatsSnapshotIntentAndTerminalMessagesAtTheProducer() {
        var task = new UseItemTask(null, new UseItemTaskRecord("call", "offhand", "mod:wand", 37, null, null, null));
        assertEquals("Use mod:wand", task.intentContract().goal());
        RuntimeLanguage.configure("pt_br");
        assertEquals("Usar mod:wand", task.intentContract().goal());
        assertEquals("O uso é aceito pelo jogo e qualquer ciclo solicitado de segurar/soltar é concluído",
                task.intentContract().successCriterion());
        var snapshot = task.snapshot();
        assertEquals("Usando item na mão", snapshot.summary());
        assertEquals("initializing", snapshot.stage());
        assertEquals("vanilla_use_accepted=false", snapshot.evidence());
        assertEquals(37, snapshot.totalUnits());
        assertEquals("Usou item na mão por 0 ticks", task.completionMessage(TaskState.SUCCESS, false));
        assertEquals("timeout: Tempo de uso do item esgotado após 0 ticks", task.completionMessage(TaskState.FAILED, true));
        assertEquals("O uso do item falhou", task.completionMessage(TaskState.FAILED, false));
    }

    @Test void immediateUseDoesNotInventAHoldDuration() {
        var task = new UseItemTask(null, new UseItemTaskRecord("call", "mainhand", null, 0, null, null, null));
        RuntimeLanguage.configure("pt_br");
        assertEquals("Usar o item na mão", task.intentContract().goal());
        assertEquals("Usou item na mão", task.completionMessage(TaskState.SUCCESS, false));
        RuntimeLanguage.configure("en_us");
        assertEquals("Used held item", task.completionMessage(TaskState.SUCCESS, false));
    }
}
