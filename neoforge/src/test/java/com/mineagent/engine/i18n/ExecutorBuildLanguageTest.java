package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.BuildTask;
import com.mineagent.tools.block.BuildTool.BuildTaskRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorBuildLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest @MethodSource("malformedRecords")
    void actualBuildValidationPublishesLocalizedFailureWithoutWorldMutation(BuildTaskRecord record, String expected) {
        RuntimeLanguage.configure("pt_br");
        var task = new BuildTask(null, record);
        task.start();
        assertEquals(TaskState.FAILED, task.tick());
        assertEquals(expected, task.snapshot().blockedReason());
        assertEquals("Tarefa de construção inválida", task.snapshot().summary());
        assertEquals("done", task.snapshot().stage());
        assertEquals("Construção rejeitada: " + expected, task.completionMessage(TaskState.FAILED, false));
        assertEquals("Construção rejeitada: " + expected, task.completionMessage(TaskState.FAILED, true));
        task.resume();
        assertEquals(TaskState.FAILED, task.tick());
        assertEquals(expected, task.snapshot().blockedReason());
    }

    private static Stream<Arguments> malformedRecords() {
        return Stream.of(
                Arguments.of(null, "O registro da tarefa de construção é nulo"),
                Arguments.of(new BuildTaskRecord("call", "invalid", "mod:block", new int[][]{{1, 2, 3}}), "O modo de construção deve ser 'place' ou 'clear'"),
                Arguments.of(new BuildTaskRecord("call", "place", null, new int[][]{{1, 2, 3}}), "Construções place exigem um tipo de bloco"),
                Arguments.of(new BuildTaskRecord("call", "clear", null, new int[0][]), "As posições de construção não podem estar vazias"),
                Arguments.of(new BuildTaskRecord("call", "clear", null, new int[513][]), "As posições de construção excedem o limite de 512 células")
        );
    }
}
