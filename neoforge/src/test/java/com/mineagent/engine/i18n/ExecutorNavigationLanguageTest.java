package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.MineBlockTask;
import com.mineagent.engine.task.MoveToTask;
import com.mineagent.tools.block.AutoMineTool.MineBlockTaskRecord;
import com.mineagent.tools.movement.MoveToTool.MoveToTaskRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorNavigationLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void miningIntentKeepsPolicyConstraintIdsAndLiteralMaterial() {
        var task = new MineBlockTask(null, new MineBlockTaskRecord("call", "mod:oak_log", 12, 32));
        var english = task.intentContract();
        assertEquals("Mine 12 blocks of mod:oak_log", english.goal());
        RuntimeLanguage.configure("pt_br");
        var translated = task.intentContract();
        assertEquals("Minerar 12 blocos de mod:oak_log", translated.goal());
        assertEquals(english.terrainPolicy(), translated.terrainPolicy());
        assertEquals("target_material", translated.constraints().get(0).id());
        assertEquals("mining", translated.constraints().get(0).scope());
        assertEquals("Não criar pilares ou pontes apenas para cortar esta árvore", translated.constraints().get(1).description());
        assertEquals("Minerou 0 blocos de mod:oak_log", task.completionMessage(TaskState.SUCCESS, false));
        assertEquals("timeout: Tempo de mineração esgotado após minerar 0/12 blocos de mod:oak_log", task.completionMessage(TaskState.FAILED, true));
        assertEquals("A mineração falhou após minerar 0/12 blocos de mod:oak_log", task.completionMessage(TaskState.FAILED, false));
    }

    @Test void nonTreeMiningKeepsTheOriginalTerrainBudget() {
        var task = new MineBlockTask(null, new MineBlockTaskRecord("call", "mod:ore", 9, 16));
        var policy = task.intentContract().terrainPolicy();
        RuntimeLanguage.configure("pt_br");
        assertEquals(policy, task.intentContract().terrainPolicy());
        assertEquals("Minimizar alterações no terreno que não seja o alvo", task.intentContract().constraints().get(1).description());
    }

    @Test void navigationFailureKeepsModeNullableCoordinatesAndRawDiagnostics() throws Exception {
        var task = new MoveToTask(null, new MoveToTaskRecord("call", "xz", -12, null, 43));
        assertEquals("Navigation failed (mode=xz target=(-12, null, 43)", task.completionMessage(TaskState.FAILED, false));
        RuntimeLanguage.configure("pt_br");
        assertEquals("navigation: A navegação falhou (mode=xz target=(-12, null, 43)", task.completionMessage(TaskState.FAILED, false));
        var field = MoveToTask.class.getDeclaredField("failReason");
        field.setAccessible(true);
        String raw = "PATH_ENDED_OUTSIDE_GOAL player=-12, 65, 43 {0} $owner";
        field.set(task, raw);
        assertEquals("navigation: A navegação falhou: " + raw + " (mode=xz target=(-12, null, 43))", task.completionMessage(TaskState.FAILED, false));
    }
}
