package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.InteractAtTask;
import com.mineagent.tools.InteractAtTool.InteractAtTaskRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorInteractionLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest @ValueSource(strings = {"use", "use_offhand", "attack"})
    void blockInteractionLocalizesProseAndKeepsTheRequestedButtonAndPredicate(String button) {
        var task = new InteractAtTask(null, new InteractAtTaskRecord("call", button, -12, 65, 43, 8, "mod:wand"));
        var english = task.intentContract();
        RuntimeLanguage.configure("pt_br");
        var snapshot = task.snapshot();
        assertEquals("Interagir com bloco usando " + button, snapshot.summary());
        assertEquals("initializing", snapshot.stage());
        assertEquals(-12, snapshot.targetX());
        assertEquals(65, snapshot.targetY());
        assertEquals(43, snapshot.targetZ());
        assertEquals("Interagir com o bloco solicitado", task.intentContract().goal());
        assertEquals("O jogo aceita a interação solicitada" + (button.equals("attack") ? " e o alvo se torna ar" : ""), task.intentContract().successCriterion());
        assertEquals(english.terrainPolicy(), task.intentContract().terrainPolicy());
        assertEquals("target_only", task.intentContract().constraints().get(0).id());
        assertEquals("A navegação não pode modificar terreno não relacionado", task.intentContract().constraints().get(0).description());
        assertEquals("Interagiu em (-12, 65, 43) com button=" + button, task.completionMessage(TaskState.SUCCESS, false));
        assertEquals("timeout: Tempo de interação esgotado em (-12, 65, 43)", task.completionMessage(TaskState.FAILED, true));
        assertEquals("A interação falhou em (-12, 65, 43)", task.completionMessage(TaskState.FAILED, false));
    }
}
