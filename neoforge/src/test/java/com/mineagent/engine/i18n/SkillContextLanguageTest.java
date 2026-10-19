package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillRuntime;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillContextLanguageTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void translatedFailuresPreserveDiagnosticDiscriminatorsUsedByMemoryAndAdapters() {
        RuntimeLanguage.configure("pt_br");
        assertEquals(com.mineagent.engine.memory.ExperienceStore.FailureKind.TIMEOUT,
                com.mineagent.engine.memory.ExperienceStore.diagnose(com.mineagent.api.task.TaskState.FAILED, null, ContextMessages.text("skill.timeout", 20)));
        assertEquals(com.mineagent.engine.memory.ExperienceStore.FailureKind.PRECONDITION,
                com.mineagent.engine.memory.ExperienceStore.diagnose(com.mineagent.api.task.TaskState.FAILED, null, ContextMessages.text("skill.precondition") + "mod:ore count=1"));
        assertTrue(ContextMessages.text("skill.postcondition").contains("postcondition"));
    }

    @Test void runtimeExplainsOnlyObservedFailureAndRetainsRawEvidence() {
        var library = new SkillLibrary();
        library.register("raw_skill", "raw description", "raw trigger", "[{\"tool\":\"auto_mine\",\"args\":{}}]", true);
        var runtime = new SkillRuntime(library, new SemanticWorldModel(),
                (id, tool, args) -> new SkillRuntime.DispatchResult(false, false, false, "raw evidence {0}"), ignored -> {});
        RuntimeLanguage.configure("pt_br");
        assertEquals("Etapas aceitas para verificação: 1", runtime.start("raw_skill", null, 1L).message());
        assertEquals("Já há uma habilidade em execução: raw_skill", runtime.start("raw_skill", null, 1L).message());
        runtime.tick(2L);
        assertEquals("etapa 1 auto_mine falhou: raw evidence {0}", runtime.snapshot().lastEvidence());
        assertEquals(SkillRuntime.Status.NEEDS_REPLAN, runtime.snapshot().status());
    }

    @Test void localizedTimeoutKeepsTheSameRetryBudgetAndTerminalState() {
        var library = new SkillLibrary();
        library.register("raw_skill", "raw description", "raw trigger", "[{\"tool\":\"auto_mine\",\"args\":{},\"timeout_ticks\":20,\"on_failure\":\"retry_once\"}]", true);
        var runtime = new SkillRuntime(library, new SemanticWorldModel(),
                (id, tool, args) -> new SkillRuntime.DispatchResult(true, true, true, "raw evidence"), ignored -> {});
        RuntimeLanguage.configure("pt_br");
        assertTrue(runtime.start("raw_skill", null, 1L).accepted());
        runtime.tick(2L);
        runtime.tick(23L);
        assertEquals(SkillRuntime.Status.RUNNING, runtime.snapshot().status());
        assertEquals("timeout: etapa excedeu o tempo limite de 20 ticks", runtime.snapshot().lastEvidence());
        runtime.tick(24L);
        runtime.tick(45L);
        assertEquals(SkillRuntime.Status.NEEDS_REPLAN, runtime.snapshot().status());
        assertEquals(2, runtime.snapshot().attempt());
    }

    @Test void runtimeSummariesAndVerifiedEvidenceAreLocalizedWithoutChangingToolIds() {
        var library = new SkillLibrary();
        library.register("raw_skill", "raw description", "raw trigger", "[{\"tool\":\"auto_mine\",\"args\":{}}]", true);
        var runtime = new SkillRuntime(library, new SemanticWorldModel(),
                (id, tool, args) -> new SkillRuntime.DispatchResult(true, false, true, "raw evidence {0}"), ignored -> {});
        RuntimeLanguage.configure("pt_br");
        assertEquals("Execução de habilidade: inativa\n", runtime.summarizeForPrompt());
        assertEquals("Habilidade aprendida não encontrada: missing_exact", runtime.start("missing_exact", null, 1L).message());
        assertTrue(runtime.start("raw_skill", null, 2L).accepted());
        runtime.tick(3L);
        assertEquals(SkillRuntime.Status.SUCCEEDED, runtime.snapshot().status());
        assertEquals("todos os efeitos da habilidade foram verificados", runtime.snapshot().lastEvidence());
        assertTrue(runtime.summarizeForPrompt().startsWith("Execução de habilidade: run="));
        assertTrue(runtime.summarizeForPrompt().contains("skill=raw_skill status=succeeded"));
    }
}
