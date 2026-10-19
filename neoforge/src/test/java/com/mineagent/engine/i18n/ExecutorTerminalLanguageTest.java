package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.*;
import com.mineagent.tools.LocateBiomeTool.LocateBiomeTaskRecord;
import com.mineagent.tools.LocateStructureTool.LocateStructureTaskRecord;
import com.mineagent.tools.block.AutoMineTool.MineBlockTaskRecord;
import com.mineagent.tools.inventory.EatItemTool.EatItemTaskRecord;
import com.mineagent.tools.inventory.UseItemTool.UseItemTaskRecord;
import com.mineagent.tools.management.WaitForTool.WaitForTaskRecord;
import com.mineagent.tools.movement.MoveToTool.MoveToTaskRecord;
import com.mineagent.tools.InteractAtTool.InteractAtTaskRecord;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorTerminalLanguageTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest @MethodSource("tasks")
    void cancellationIsLocalizedAtTheConcreteExecutor(CompanionTask<?> task) {
        assertEquals("cancelled", task.completionMessage(TaskState.CANCELLED, false));
        RuntimeLanguage.configure("pt_br");
        assertEquals("cancelado", task.completionMessage(TaskState.CANCELLED, false));
    }

    @Test void missingWaitKindUsesLocalizedProseRatherThanAnEnglishFallback() {
        RuntimeLanguage.configure("pt_br");
        assertEquals("Aguardar condição", new WaitForTask(null, null).intentContract().goal());
    }

    @Test void structureAndBiomeMissingResultsKeepTheExistingDiagnosisToken() {
        RuntimeLanguage.configure("pt_br");
        assertTrue(ExecutorMessages.text("biome.missing", "mod:biome", 6400).contains("not found"));
        assertTrue(ExecutorMessages.text("structure.missing", "#mod:structure", 100).contains("not found"));
    }

    private static Stream<CompanionTask<?>> tasks() {
        return Stream.of(new MeleeAttackTask(null, null), new RangedAttackTask(null, null),
                new InteractEntityTask(null, null), new WaitForTask(null, null), new BuildTask(null, null),
                new MineBlockTask(null, new MineBlockTaskRecord("call", "mod:block", 1, 8)),
                new MoveToTask(null, new MoveToTaskRecord("call", "xz", 1, null, 3)),
                new LocateBiomeTask(null, new LocateBiomeTaskRecord("call", "mod:biome")),
                new LocateStructureTask(null, new LocateStructureTaskRecord("call", "#mod:structure")),
                new UseItemTask(null, new UseItemTaskRecord("call", "mainhand", null, 0, null, null, null)),
                new EatItemTask(null, new EatItemTaskRecord("call", null)),
                new InteractAtTask(null, new InteractAtTaskRecord("call", "use", 1, 2, 3, 1, null)));
    }
}
