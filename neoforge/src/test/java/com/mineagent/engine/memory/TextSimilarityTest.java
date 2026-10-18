package com.mineagent.engine.memory;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.skill.SkillLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TextSimilarityTest {
    @Test void relatedEnglishWordsOutrankUnrelatedText() {
        double related = TextSimilarity.score("collect wood to build a house", "chop trees and collect wood to build");
        double unrelated = TextSimilarity.score("collect wood to build a house", "find diamonds and fight zombies");
        assertTrue(related > unrelated);
    }

    @Test void learnedParameterizedSkillCanBeRetrievedInEnglish() {
        SkillLibrary library = new SkillLibrary();
        library.registerSequence("wood_collection", "chop trees and collect wood", "when wood is needed to build",
                "[{\"tool\":\"auto_mine\",\"args\":{\"block_id\":\"minecraft:oak_log\"}}]",
                true);
        assertFalse(library.retrieve("please collect some wood to build a house").isEmpty());
    }

    @Test void successfulRelevantExperienceIsNotHiddenByUnrelatedFailure() {
        ExperienceStore store = new ExperienceStore();
        store.record("1", "auto_mine", "collect wood", TaskState.SUCCESS,
                TaskSnapshot.running("complete", "logs collected"), "oak logs", 10L);
        store.record("2", "goto", "find a village", TaskState.FAILED,
                TaskSnapshot.running("navigate", "failed"), "path timeout", 20L);
        String summary = store.summarizeForPrompt("collect wood to build");
        assertTrue(summary.contains("auto_mine"));
        assertFalse(summary.contains("path timeout"));
    }

    @Test void failedAdaptationDoesNotOverwriteVerifiedSkillTrace() {
        SkillLibrary library = new SkillLibrary();
        library.registerSequence("bridge", "safe bridge", "cross gap",
                "[{\"tool\":\"build\",\"args\":{\"block\":\"stone\"}}]", true);
        library.registerSequence("bridge", "unsafe retry", "cross gap",
                "[{\"tool\":\"build\",\"args\":{\"block\":\"sand\"}}]", false);
        assertTrue(library.getActionSequence("bridge").contains("stone"));
        assertFalse(library.getActionSequence("bridge").contains("sand"));
    }
}
