package com.mineagent.engine.persona;

import java.util.Random;

/**
 * Personality profile — stable traits unique to each companion AI.
 *
 * <p>Inspired by the five-factor OCEAN model and persistent personality drift in VoxelMind.
 * Important caveat: a prompt saying "you are introverted" is not enough (personality illusion research);
 * structured constraints are also needed at the behavior layer.
 *
 * <p>This class defines a five-dimensional personality vector and behavioral constraints:
 * <ul>
 *   <li>Extraversion: affects spontaneous speech and social distance</li>
 *   <li>Agreeableness: affects cooperation and aggression</li>
 *   <li>Conscientiousness: affects planning and caution</li>
 *   <li>Neuroticism: affects emotional volatility and fear responses</li>
 *   <li>Openness: affects exploration and curiosity</li>
 * </ul>
 *
 * <p>The personality vector persists across sessions but drifts slowly with experience.
 */
public class PersonaProfile {

    // Five OCEAN dimensions [0, 1]
    private float openness;       // Openness: exploration and curiosity
    private float conscientiousness; // Conscientiousness: planning and caution
    private float extraversion;   // Extraversion: social activity
    private float agreeableness;  // Agreeableness: cooperation and gentleness
    private float neuroticism;    // Neuroticism: emotional volatility

    /** Personality labels (for prompt context). */
    private final String[] traits;

    /** Drift rate: how much each experience changes personality. */
    private static final float DRIFT_RATE = 0.002f;

    public PersonaProfile(float openness, float conscientiousness,
                           float extraversion, float agreeableness,
                           float neuroticism, String[] traits) {
        this.openness = clamp(openness);
        this.conscientiousness = clamp(conscientiousness);
        this.extraversion = clamp(extraversion);
        this.agreeableness = clamp(agreeableness);
        this.neuroticism = clamp(neuroticism);
        this.traits = traits != null ? traits : new String[0];
    }

    /**
     * Generate a random personality profile.
     */
    public static PersonaProfile random() {
        Random rng = new Random();
        return new PersonaProfile(
                rng.nextFloat(),
                rng.nextFloat(),
                rng.nextFloat(),
                rng.nextFloat(),
                rng.nextFloat(),
                generateTraits(rng)
        );
    }

    private static String[] generateTraits(Random rng) {
        // Generate natural-language personality descriptions from the five dimensions
        java.util.List<String> traits = new java.util.ArrayList<>();
        if (rng.nextBoolean()) traits.add("enjoys exploring");
        if (rng.nextBoolean()) traits.add("organized");
        if (rng.nextBoolean()) traits.add("talkative");
        if (rng.nextBoolean()) traits.add("cautious");
        if (rng.nextBoolean()) traits.add("helpful");
        if (traits.isEmpty()) traits.add("easygoing");
        return traits.toArray(new String[0]);
    }

    // ── Behavioral constraint methods ──

    /**
     * Whether to speak spontaneously (influenced by extraversion).
     *
     * @param baseProbability base probability of speaking
     * @return whether to speak
     */
    public boolean shouldSpeak(float baseProbability) {
        // Higher extraversion → more likely to speak
        float adjusted = baseProbability * (0.5f + extraversion);
        return Math.random() < adjusted;
    }

    /**
     * Ideal social distance (influenced by extraversion).
     * Extraverted → stand closer; introverted → stand farther away.
     */
    public float idealSocialDistance() {
        // Extraversion 0 → 4 blocks; extraversion 1 → 1.5 blocks
        return 4.0f - extraversion * 2.5f;
    }

    /**
     * Exploration tendency (influenced by openness).
     * Higher openness → more willing to explore unknown areas.
     */
    public float explorationTendency() {
        return openness;
    }

    /**
     * Planning tendency (influenced by conscientiousness).
     * Higher conscientiousness → more likely to plan before acting.
     */
    public float planningTendency() {
        return conscientiousness;
    }

    /**
     * Fear response strength (influenced by neuroticism).
     * Higher neuroticism → more likely to fear monsters or retreat.
     */
    public float fearResponse() {
        return neuroticism;
    }

    /**
     * Cooperation tendency (influenced by agreeableness).
     * Higher agreeableness → cooperate with the player rather than steal kills.
     */
    public float cooperationTendency() {
        return agreeableness;
    }

    /**
     * Personality drift through experience.
     * Positive experiences (praise, success) → slightly higher extraversion and agreeableness.
     * Negative experiences (criticism, failure) → higher neuroticism and lower agreeableness.
     *
     * @param positive true for a positive experience, false for a negative one
     */
    public void drift(boolean positive) {
        if (positive) {
            extraversion = clamp(extraversion + DRIFT_RATE);
            agreeableness = clamp(agreeableness + DRIFT_RATE * 0.5f);
            neuroticism = clamp(neuroticism - DRIFT_RATE * 0.3f);
        } else {
            neuroticism = clamp(neuroticism + DRIFT_RATE);
            agreeableness = clamp(agreeableness - DRIFT_RATE * 0.5f);
            extraversion = clamp(extraversion - DRIFT_RATE * 0.3f);
        }
    }

    /**
     * Generate a personality description for the LLM system prompt.
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Your personality\n");
        for (String trait : traits) {
            sb.append("- ").append(trait).append("\n");
        }
        sb.append(String.format(
                "- Exploration: %.0f%% | Planning: %.0f%% | Social activity: %.0f%% | Cooperation: %.0f%% | Emotional sensitivity: %.0f%%\n",
                openness * 100, conscientiousness * 100,
                extraversion * 100, agreeableness * 100, neuroticism * 100));

        // Behavioral guidance (ensures personality is more than descriptive text)
        sb.append("Act according to your personality:");
        if (openness > 0.6f) sb.append(" You are curious and enjoy visiting unfamiliar places.");
        if (conscientiousness > 0.6f) sb.append(" You are organized and prepare your tools before setting out.");
        if (extraversion > 0.6f) sb.append(" You are talkative and initiate conversations with the player.");
        else if (extraversion < 0.3f) sb.append(" You are quiet and do not initiate conversations, but answer when asked.");
        if (neuroticism > 0.6f) sb.append(" You are timid and tend to retreat first when facing danger.");
        if (agreeableness > 0.6f) sb.append(" You are cooperative and prioritize helping the player.");
        sb.append("\n");
        return sb.toString();
    }

    // ── Getters ──

    public float openness() { return openness; }
    public float conscientiousness() { return conscientiousness; }
    public float extraversion() { return extraversion; }
    public float agreeableness() { return agreeableness; }
    public float neuroticism() { return neuroticism; }
    public String[] traits() { return traits; }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
