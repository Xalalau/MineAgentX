package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.WaitForTask;
import com.mineagent.tools.management.WaitForTool.WaitForTaskRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorWaitLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void waitSnapshotAndIntentLocalizeWithoutChangingConditionOrEvidence() {
        var task = new WaitForTask(null, record("menu_slot"));
        assertEquals("Waiting for menu_slot", task.snapshot().summary());
        RuntimeLanguage.configure("pt_br");
        var snapshot = task.snapshot();
        assertEquals("Aguardando menu_slot", snapshot.summary());
        assertEquals("menu_slot", snapshot.stage());
        assertEquals("elapsed_ticks=0 stable=0/7", snapshot.evidence());
        assertEquals(7, snapshot.totalUnits());
        assertEquals("Aguardar menu_slot", task.intentContract().goal());
        assertEquals("A condição solicitada do servidor permanece verdadeira durante a janela de estabilidade exigida",
                task.intentContract().successCriterion());
        assertEquals("Condição 'menu_slot' verificada por 7 ticks", task.completionMessage(TaskState.SUCCESS, false));
    }

    @Test void malformedWaitPublishesTheActualLocalizedFailureWithoutWorldAccess() {
        RuntimeLanguage.configure("pt_br");
        var task = new WaitForTask(null, null);
        assertEquals(TaskState.FAILED, task.tick());
        assertEquals("O registro da tarefa de espera é nulo", task.completionMessage(TaskState.FAILED, false));
        assertEquals(task.completionMessage(TaskState.FAILED, false), task.snapshot().blockedReason());
        task.interrupt();
        task.resume();
        assertEquals(TaskState.FAILED, task.tick());
        assertEquals("O registro da tarefa de espera é nulo", task.completionMessage(TaskState.FAILED, false));
    }

    @Test void missingKindAndTerminalFallbacksAreLocalized() {
        RuntimeLanguage.configure("pt_br");
        var invalid = new WaitForTask(null, record(null));
        assertEquals(TaskState.FAILED, invalid.tick());
        assertEquals("O tipo da tarefa de espera está ausente", invalid.completionMessage(TaskState.FAILED, false));
        var pending = new WaitForTask(null, record("duration"));
        assertEquals("A condição de espera falhou", pending.completionMessage(TaskState.FAILED, false));
        assertEquals("timeout: Tempo da condição de espera esgotado", pending.completionMessage(TaskState.FAILED, true));
    }

    private static WaitForTaskRecord record(String kind) {
        return new WaitForTaskRecord("call-literal", kind, 30, "mod:food", 3,
                "owner words", "custom:predicate", "literal {0}", "equals", "player", 4,
                "mod:dimension", 1, 2, -3, "mod:block", "mod:entity", 8.0, true, 7);
    }
}
