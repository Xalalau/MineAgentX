package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.LocateBiomeTask;
import com.mineagent.engine.task.LocateStructureTask;
import com.mineagent.tools.LocateBiomeTool.LocateBiomeTaskRecord;
import com.mineagent.tools.LocateStructureTool.LocateStructureTaskRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorSearchLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void biomeSearchLifecycleLocalizesObservationsAndKeepsLiteralIds() {
        var task = new LocateBiomeTask(null, new LocateBiomeTaskRecord("call", "mod:silent_forest"));
        task.start();
        assertEquals("Locate biome mod:silent_forest", task.snapshot().summary());
        RuntimeLanguage.configure("pt_br");
        assertEquals("Localizar bioma mod:silent_forest", task.snapshot().summary());
        assertEquals("searching", task.snapshot().stage());
        assertEquals(0, task.snapshot().completedUnits());
        assertEquals(1, task.snapshot().totalUnits());
        assertNull(task.snapshot().evidence());
        assertEquals("A busca pelo bioma 'mod:silent_forest' falhou", task.completionMessage(TaskState.FAILED, false));
        assertEquals("timeout: Tempo de busca pelo bioma 'mod:silent_forest' esgotado", task.completionMessage(TaskState.FAILED, true));
        task.interrupt();
        task.resume();
        assertEquals("Localizar bioma mod:silent_forest", task.snapshot().summary());
    }

    @Test void structureSearchKeepsExplicitTagAndTaskStage() {
        var task = new LocateStructureTask(null, new LocateStructureTaskRecord("call", "#mod:temples"));
        task.start();
        RuntimeLanguage.configure("pt_br");
        assertEquals("Localizar estrutura #mod:temples", task.snapshot().summary());
        assertEquals("searching", task.snapshot().stage());
        assertEquals("A busca pela estrutura '#mod:temples' falhou", task.completionMessage(TaskState.FAILED, false));
        assertEquals("timeout: Tempo de busca pela estrutura '#mod:temples' esgotado", task.completionMessage(TaskState.FAILED, true));
        task.interrupt();
        task.resume();
        RuntimeLanguage.configure("en_us");
        assertEquals("Locate structure #mod:temples", task.snapshot().summary());
    }
}
