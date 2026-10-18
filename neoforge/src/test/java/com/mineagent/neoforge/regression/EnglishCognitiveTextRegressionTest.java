package com.mineagent.neoforge.regression;

import com.mineagent.engine.memory.ImportanceEvaluator;
import com.mineagent.engine.memory.PlaceEventMemory;
import com.mineagent.engine.memory.ReflectionSystem;
import com.mineagent.engine.persona.EmotionState;
import com.mineagent.engine.persona.PersonaProfile;
import com.mineagent.engine.theory.TheoryOfMind;

/** English prompt labels must stay compatible with the consuming agent loop. */
public final class EnglishCognitiveTextRegressionTest {
    @org.junit.jupiter.api.Test
    void cognitiveLabelsAndIntentHeuristicsRemainUsableInEnglish() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        EmotionState emotion = new EmotionState();
        require(emotion.label().equals("calm"), "Default emotion must be calm in English");
        emotion.triggerEvent(1f, 1f, 0f, 1f);
        require(emotion.label().equals("excited"), "Existing PAD thresholds must retain their meaning");
        require(emotion.toPromptString().contains("do not affect your decision logic"),
                "Emotion prompt must retain the rational-decision safeguard");

        PersonaProfile persona = new PersonaProfile(0.8f, 0.8f, 0.8f, 0.8f, 0.8f,
                new String[]{"helpful"});
        require(persona.toPromptString().contains("## Your personality"), "English personality heading");
        require(persona.toPromptString().contains("helpful"), "Caller-provided traits must be preserved");
        require(persona.toPromptString().contains("Act according to your personality:"),
                "Existing personality guidance must not be removed");

        String[][] examples = {
                {"dig", "MINING"}, {"construct", "BUILDING"}, {"attack", "FIGHTING"},
                {"plant", "FARMING"}, {"villager", "TRADING"}, {"retreat", "FLEEING"},
                {"travel", "EXPLORING"}
        };
        for (String[] example : examples) {
            TheoryOfMind theory = new TheoryOfMind();
            theory.observe(example[0], 0L);
            require(theory.currentIntent().name().equals(example[1]),
                    "English intent heuristic: " + example[0]);
        }
        require(TheoryOfMind.PlayerIntent.MINING.desc().equals("is mining"), "English mining label");

        PlaceEventMemory places = new PlaceEventMemory();
        require(places.summarizeForPrompt().equals("(No location memories yet)"),
                "Stable empty-location label used by AgentLoop");

        ReflectionSystem reflections = new ReflectionSystem();
        reflections.recordToolFailure("goto", "{}", "timeout", "travel");
        require(reflections.exportAll().getFirst().lesson().contains("timed out"),
                "Timeout lessons must retain actionable meaning in English");
        require(reflections.summarizeForPrompt().contains("## Lessons to remember"),
                "English reflection heading");
        reflections.recordTaskFailure("travel to village", "blocked", "forest");
        require(reflections.formatFailuresForPrompt("travel village").lines()
                        .anyMatch(line -> line.startsWith("Lesson:")),
                "AgentLoop must recognize the standalone Lesson: line in failure memories");

        ImportanceEvaluator importance = new ImportanceEvaluator();
        importance.learnFromLLMAnnotation("scan", "{}", "important");
        require(importance.getWeight("scan", "{}") > 0.5f,
                "Translated high-importance alias must still increase weight");
        System.out.println("English cognitive text regression checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
