package com.mineagent.engine.persona;

/**
 * PAD emotion state machine — three-dimensional Pleasure-Arousal-Dominance model.
 *
 * <p>Inspired by the dual-speed emotion dynamics in SENTIPOLIS (CMU 2026).
 * Addresses emotional amnesia: criticism should not be forgotten on the very next turn.
 *
 * <p>Three-layer design:
 * <ul>
 *   <li><b>Fast emotions</b> (seconds): a three-dimensional Pleasure-Arousal-Dominance vector</li>
 *   <li><b>Slow mood</b> (hours): a baseline that influences all fast emotions</li>
 *   <li><b>Emotion-memory coupling</b>: events retain emotional labels that influence interpretation on recall</li>
 * </ul>
 *
 * <p>Emotions influence:
 * <ul>
 *   <li>Conversational style (happy → more talkative, sad → quieter)</li>
 *   <li>Action selection (high arousal → fast action, low arousal → slow)</li>
 *   <li>Pause duration (emotion affects thinking pauses)</li>
 *   <li>System prompt context (lets the LLM perceive the emotion)</li>
 * </ul>
 */
public class EmotionState {

    // ── Fast PAD emotion vector [-1, 1] ──
    private float pleasure;  // -1=extreme displeasure, +1=extreme pleasure
    private float arousal;   // -1=calm, +1=high excitement/alertness
    private float dominance; // -1=controlled/powerless, +1=in control/confident

    // ── Slow mood baseline [-0.3, 0.3] (weaker but longer-lived) ──
    private float moodPleasure;
    private float moodArousal;
    private float moodDominance;

    // ── Decay rates ──
    /** Fast emotion decay per tick (toward the mood baseline).
     *  Reduced from 0.04 to 0.015 so emotions last about 3 seconds rather than 1.25 seconds.
     *  This gives emotion-based learning (ImportanceEvaluator.learnFromEmotion)
     *  enough time to detect emotional changes instead of missing them entirely.
     *  Emotions still must not distort rational decisions for long. */
    private static final float EMOTION_DECAY = 0.015f;
    /** Mood decay per tick (toward 0). */
    private static final float MOOD_DECAY = 0.001f;
    /** Strength of the mood's pull on fast emotions. */
    private static final float MOOD_PULL = 0.005f;

    // ── Latest emotion label (for prompt context) ──
    private String lastEmotionLabel = "calm";

    public EmotionState() {
        this.pleasure = 0;
        this.arousal = 0;
        this.dominance = 0;
        this.moodPleasure = 0;
        this.moodArousal = 0;
        this.moodDominance = 0;
    }

    /**
     * Trigger an emotional event.
     *
     * @param eventPleasure change in pleasure caused by the event [-1, 1]
     * @param eventArousal change in arousal caused by the event [-1, 1]
     * @param eventDominance change in dominance caused by the event [-1, 1]
     * @param intensity event intensity [0, 1] (1=extremely strong)
     */
    public void triggerEvent(float eventPleasure, float eventArousal,
                              float eventDominance, float intensity) {
        pleasure = clamp(pleasure + eventPleasure * intensity);
        arousal = clamp(arousal + eventArousal * intensity);
        dominance = clamp(dominance + eventDominance * intensity);

        // Strong events also affect the mood
        if (intensity > 0.5f) {
            float moodImpact = (intensity - 0.5f) * 0.1f;
            moodPleasure = clampMood(moodPleasure + eventPleasure * moodImpact);
            moodArousal = clampMood(moodArousal + eventArousal * moodImpact);
            moodDominance = clampMood(moodDominance + eventDominance * moodImpact);
        }

        updateLabel();
    }

    /**
     * Call once per tick to let emotions decay naturally.
     * Fast emotions approach the mood baseline; mood approaches 0.
     */
    public void tick() {
        // Fast emotions decay toward the mood baseline
        pleasure = approach(pleasure, moodPleasure, EMOTION_DECAY);
        arousal = approach(arousal, moodArousal, EMOTION_DECAY);
        dominance = approach(dominance, moodDominance, EMOTION_DECAY);

        // Mood decays toward 0 (very slowly)
        moodPleasure = approach(moodPleasure, 0, MOOD_DECAY);
        moodArousal = approach(moodArousal, 0, MOOD_DECAY);
        moodDominance = approach(moodDominance, 0, MOOD_DECAY);

        updateLabel();
    }

    /**
     * Generate an emotional description for the LLM system prompt.
     *
     * <p><b>Design principle: emotion < reason</b>
     * Emotions are injected only as context describing the character's current state,
     * <b>not</b> as instructions to change behavior (such as talking more or moving faster).
     * The LLM should make decisions through rational analysis; emotion affects tone and wording,
     * not the soundness of the decision itself.
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Your current emotions\n");
        sb.append(String.format("Current emotion: %s (pleasure %.0f%% | activity %.0f%% | confidence %.0f%%)\n",
                lastEmotionLabel,
                (pleasure + 1) * 50, (arousal + 1) * 50, (dominance + 1) * 50));
        // Describe the emotional state without directing behavior
        // Reason first: emotions affect tone, not decisions
        sb.append("Note: emotions affect only your tone and wording; ");
        sb.append("they do not affect your decision logic. ");
        sb.append("Regardless of your emotions, always make the best decision through rational analysis.\n");
        return sb.toString();
    }

    // ── Predefined emotional events ──
    // Intensities are reduced to about 60% of their original values to protect rationality.
    // Design principle: emotion is seasoning, not the main course.

    /** Attacked by a monster */
    public void onAttacked() {
        triggerEvent(-0.4f, 0.5f, -0.3f, 0.5f);
    }

    /** See danger */
    public void onSeeDanger() {
        triggerEvent(-0.2f, 0.4f, -0.1f, 0.3f);
    }

    /** Find a valuable resource (such as diamonds) */
    public void onFindTreasure() {
        triggerEvent(0.5f, 0.3f, 0.2f, 0.4f);
    }

    /** Complete a task */
    public void onTaskComplete() {
        triggerEvent(0.3f, 0.1f, 0.2f, 0.3f);
    }

    /** Praised by the player */
    public void onPraised() {
        triggerEvent(0.4f, 0.2f, 0.2f, 0.4f);
    }

    /** Criticized by the player */
    public void onScolded() {
        triggerEvent(-0.3f, 0.1f, -0.2f, 0.3f);
    }

    /** Fail a task */
    public void onTaskFailed() {
        triggerEvent(-0.2f, 0.2f, -0.2f, 0.3f);
    }

    /** Owner is hurt */
    public void onOwnerHurt() {
        triggerEvent(-0.2f, 0.4f, -0.2f, 0.3f);
    }

    // ── Getters ──

    public float pleasure() { return pleasure; }
    public float arousal() { return arousal; }
    public float dominance() { return dominance; }
    public String label() { return lastEmotionLabel; }

    // ── Internal methods ──

    private void updateLabel() {
        // Map PAD values to semantic emotion labels
        if (pleasure > 0.5f && arousal > 0.3f) lastEmotionLabel = "excited";
        else if (pleasure > 0.5f && arousal < -0.2f) lastEmotionLabel = "content";
        else if (pleasure > 0.3f) lastEmotionLabel = "happy";
        else if (pleasure < -0.5f && arousal > 0.5f) lastEmotionLabel = "angry";
        else if (pleasure < -0.5f && arousal < -0.2f) lastEmotionLabel = "dejected";
        else if (pleasure < -0.3f && arousal > 0.4f) lastEmotionLabel = "afraid";
        else if (pleasure < -0.3f) lastEmotionLabel = "displeased";
        else if (arousal > 0.5f) lastEmotionLabel = "alert";
        else if (arousal < -0.3f) lastEmotionLabel = "calm";
        else lastEmotionLabel = "calm";
    }

    private static float approach(float current, float target, float rate) {
        if (current > target) {
            return Math.max(target, current - rate);
        } else {
            return Math.min(target, current + rate);
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float clampMood(float v) {
        return Math.max(-0.3f, Math.min(0.3f, v));
    }
}
