package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.memory.ExperienceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorDiagnosticLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest @MethodSource("messages")
    void localizedFailuresKeepTheExistingDiagnosisCategories(String key, Object[] args) {
        RuntimeLanguage.configure("en_us");
        var expected = ExperienceStore.diagnose(TaskState.FAILED, null, ExecutorMessages.text(key, args));
        RuntimeLanguage.configure("pt_br");
        var actual = ExperienceStore.diagnose(TaskState.FAILED, null, ExecutorMessages.text(key, args));
        assertEquals(expected, actual, key);
    }

    private static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of("wait.elapsed", new Object[]{"literal:observed"}),
                Arguments.of("wait.timeout", new Object[]{}),
                Arguments.of("use.missing", new Object[]{"literal:observed"}),
                Arguments.of("use.deadline", new Object[]{}),
                Arguments.of("use.timeout", new Object[]{"literal:observed"}),
                Arguments.of("nav.lease", new Object[]{"literal:observed"}),
                Arguments.of("nav.failed", new Object[]{}),
                Arguments.of("nav.timeout", new Object[]{"literal:observed", "literal:observed", "literal:observed", "literal:observed", "literal:observed", "literal:observed", "literal:observed", "literal:observed"}),
                Arguments.of("nav.failureReason", new Object[]{"literal:observed", "literal:observed", "literal:observed", "literal:observed", "literal:observed"}),
                Arguments.of("nav.failureTarget", new Object[]{"literal:observed", "literal:observed", "literal:observed", "literal:observed"}),
                Arguments.of("mine.navigation", new Object[]{"literal:observed"}),
                Arguments.of("mine.stalled", new Object[]{"literal:observed", "literal:observed"}),
                Arguments.of("mine.noTool", new Object[]{"literal:observed"}),
                Arguments.of("mine.breakTimeout", new Object[]{"literal:observed", "literal:observed"}),
                Arguments.of("mine.stopped", new Object[]{"literal:observed", "literal:observed"}),
                Arguments.of("mine.timeout", new Object[]{"literal:observed", "literal:observed", "literal:observed"}),
                Arguments.of("biome.timeout", new Object[]{"literal:observed"}),
                Arguments.of("structure.timeout", new Object[]{"literal:observed"}),
                Arguments.of("eat.deadline", new Object[]{}),
                Arguments.of("eat.timeout", new Object[]{"literal:observed"}),
                Arguments.of("interact.navigation", new Object[]{"literal:observed"}),
                Arguments.of("interact.breakTimeout", new Object[]{}),
                Arguments.of("interact.timeout", new Object[]{"literal:observed", "literal:observed", "literal:observed"})
        );
    }
}
