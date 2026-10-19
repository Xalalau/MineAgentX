package com.mineagent.engine.memory;

import com.mineagent.engine.i18n.ContextMessages;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic importance evaluator — replaces fixed keyword lists.
 *
 * <p><b>Design philosophy</b>: what matters depends on the situation.
 * Diamonds matter during mining, health during combat, and materials during building.
 * A fixed keyword list cannot make that decision universally.
 *
 * <p><b>Dynamic learning</b> through three channels:
 * <ol>
 *   <li><b>LLM self-assessment</b>: the LLM can annotate tool results with an importance field,
 *       which teaches the system which patterns matter</li>
 *   <li><b>Emotion-based inference</b>: events causing strong emotional changes receive higher weights
 *       (being attacked → +1 for that event class; finding diamonds → +1 for that event class)</li>
 *   <li><b>Failure association</b>: tool results that cause task failure are automatically marked important</li>
 * </ol>
 *
 * <p><b>Improvements:</b>
 * <ul>
 *   <li>Slower decay: reduced from 0.01/tick to 0.002/tick so learned weights last longer</li>
 *   <li>Better feature extraction: combine multiple fields to reduce feature collisions</li>
 *   <li>Context awareness: adjust importance dynamically based on the current task type</li>
 * </ul>
 */
public class ImportanceEvaluator {

    /**
     * Event feature → importance weight [0, 1].
     * Feature = tool name + the first N meaningful tokens of the result.
     */
    private final Map<String, Float> featureWeights = new ConcurrentHashMap<>();

    /** Weight adjustment steps: +0.15 per learning event, -0.002/tick decay. */
    private static final float LEARN_STEP = 0.15f;
    private static final float DECAY_STEP = 0.002f; // Reduce decay from 0.01 to 0.002
    private static final float MAX_WEIGHT = 1.0f;
    private static final float MIN_WEIGHT = 0.0f;
    private static final float DEFAULT_WEIGHT = 0.5f;
    private static final float IMPORTANT_THRESHOLD = 0.65f;

    /** Learning event count (to determine whether enough data is available). */
    private final AtomicInteger learnCount = new AtomicInteger(0);

    /** Current task context (for dynamic importance adjustments) */
    private volatile String currentTaskContext = "";

    /**
     * Set the current task context.
     * During mining tasks, temporarily boost ore-related features.
     */
    public void setTaskContext(String taskDescription) {
        this.currentTaskContext = taskDescription != null ? taskDescription.toLowerCase() : "";
    }

    /**
     * Extract a feature from a tool result.
     * Improvement: combine multiple fields to reduce feature collisions.
     */
    private String extractFeature(String toolName, String resultContent) {
        if (resultContent == null) resultContent = "";

        // Try combining multiple fields into a more precise feature
        String type = extractJsonField(resultContent, "type");
        String name = extractJsonField(resultContent, "name");
        String block = extractJsonField(resultContent, "block");
        String entity = extractJsonField(resultContent, "entity");
        String item = extractJsonField(resultContent, "item");
        String target = extractJsonField(resultContent, "target");

        // Combine features, preferring the most specific fields
        StringBuilder feature = new StringBuilder(toolName).append(":");

        if (block != null && !block.isEmpty()) {
            feature.append("block=").append(block);
        } else if (entity != null && !entity.isEmpty()) {
            feature.append("entity=").append(entity);
        } else if (item != null && !item.isEmpty()) {
            feature.append("item=").append(item);
        } else if (type != null && !type.isEmpty()) {
            feature.append("type=").append(type);
            if (name != null && !name.isEmpty()) {
                feature.append("|name=").append(name);
            }
        } else if (target != null && !target.isEmpty()) {
            feature.append("target=").append(target);
        } else {
            // No matching fields: use the result content hash as a fallback feature
            // Hash the first 20 characters so long text does not make the feature overly specific
            String content = resultContent.length() > 20 ?
                    resultContent.substring(0, 20) : resultContent;
            feature.append("content=").append(content.hashCode() % 1000);
        }

        return feature.toString();
    }

    /** Extract a named field value from a JSON result. */
    private String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        String key = "\"" + field + "\":\"";
        int idx = json.toLowerCase().indexOf(key);
        if (idx >= 0) {
            int start = idx + key.length();
            int end = json.indexOf('"', start);
            if (end > start) {
                return json.substring(start, end).toLowerCase();
            }
        }
        return null;
    }

    /**
     * Look up the importance weight of a tool result.
     * Adjust dynamically using the current task context.
     */
    public float getWeight(String toolName, String resultContent) {
        String feature = extractFeature(toolName, resultContent);
        float baseWeight = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);

        // Context awareness: temporarily boost features relevant to the current task
        if (!currentTaskContext.isEmpty()) {
            String lowerFeature = feature.toLowerCase();
            // Match task keywords
            if (isTaskRelevant(lowerFeature)) {
                // Temporarily boost weight by 20%
                return Math.min(MAX_WEIGHT, baseWeight * 1.2f);
            }
        }

        return baseWeight;
    }

    /**
     * Determine whether a feature is relevant to the current task
     */
    private boolean isTaskRelevant(String feature) {
        if (currentTaskContext.isEmpty()) return false;

        // Extract task keywords
        String[] taskKeywords = currentTaskContext.split("\\s+");
        for (String keyword : taskKeywords) {
            if (keyword.length() >= 3 && feature.contains(keyword)) {
                return true;
            }
        }

        // Match common task types
        if (currentTaskContext.contains("dig") || currentTaskContext.contains("mine") ||
            currentTaskContext.contains("mining")) {
            return feature.contains("ore") || feature.contains("block") ||
                   feature.contains("stone") || feature.contains("diamond") ||
                   feature.contains("iron") || feature.contains("coal");
        }
        if (currentTaskContext.contains("construct") || currentTaskContext.contains("build") ||
            currentTaskContext.contains("place")) {
            return feature.contains("block") || feature.contains("place") ||
                   feature.contains("build") || feature.contains("craft");
        }
        if (currentTaskContext.contains("combat") || currentTaskContext.contains("fight") ||
            currentTaskContext.contains("attack")) {
            return feature.contains("entity") || feature.contains("mob") ||
                   feature.contains("damage") || feature.contains("health");
        }

        return false;
    }

    /**
     * Determine whether a tool result is important (weight reaches the threshold).
     */
    public boolean isImportant(String toolName, String resultContent) {
        return getWeight(toolName, resultContent) >= IMPORTANT_THRESHOLD;
    }

    /**
     * Learn from LLM annotations.
     * Tool results may include an "importance": "high"/"medium"/"low" field.
     */
    public void learnFromLLMAnnotation(String toolName, String resultContent, String importanceLabel) {
        if (importanceLabel == null) return;
        String feature = extractFeature(toolName, resultContent);
        float target;
        switch (importanceLabel.toLowerCase()) {
            case "high", "important", "critical" -> target = 0.9f;
            case "medium", "moderate", "normal" -> target = 0.5f;
            case "low", "minor", "trivial" -> target = 0.2f;
            default -> { return; }
        }
        float current = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);
        // Move toward the target (learning rate 0.3)
        float updated = current + (target - current) * 0.3f;
        featureWeights.put(feature, clamp(updated));
        learnCount.incrementAndGet();
    }

    /**
     * Learn by inferring importance from emotional intensity.
     * Boost features of events that cause strong emotional changes.
     */
    public void learnFromEmotion(String toolName, String resultContent, float emotionDelta) {
        // Threshold rationale: a single EmotionState adjustment is at most about 0.09
        // (one step such as onTaskFailed/onTaskComplete), or about 0.135
        // after negative events are amplified 1.5x. The old 0.2f threshold was unreachable,
        // making the whole emotion-learning channel dead code. Use 0.05f to match
        // AgentLoop's learning trigger (emotionDelta > 0.05f).
        if (Math.abs(emotionDelta) < 0.05f) return;  // Do not learn from tiny emotional changes
        String feature = extractFeature(toolName, resultContent);
        float current = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);
        // Larger emotional changes produce larger weight boosts
        // Individual emotional changes are small (0.05~0.13), too weak to use directly as boosts,
        // so amplify them 3x to make learning visible while respecting MAX_WEIGHT.
        float boost = Math.abs(emotionDelta) * 3.0f * LEARN_STEP;
        // Negative emotions (such as being attacked) get a larger boost: survival matters more
        if (emotionDelta < 0) boost *= 1.5f;
        featureWeights.put(feature, clamp(current + boost));
        learnCount.incrementAndGet();
    }

    /**
     * Learn through failure association.
     * Automatically mark tool results that caused task failure as important.
     */
    public void learnFromFailure(String toolName, String resultContent) {
        String feature = extractFeature(toolName, resultContent);
        float current = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);
        featureWeights.put(feature, clamp(current + LEARN_STEP));
        learnCount.incrementAndGet();
    }

    /**
     * Periodically decay all weights toward the neutral value 0.5.
     * Prevents feature weights from staying at 1.0 forever.
     * Recommended: call every 200 ticks.
     *
     * Decay was reduced from 0.01 to 0.002; learned weights now lose about 0.01 over 5 seconds,
     * allowing learning to last longer.
     */
    public void decayAll() {
        if (featureWeights.isEmpty()) return;
        featureWeights.replaceAll((k, v) -> {
            if (v > DEFAULT_WEIGHT) return Math.max(DEFAULT_WEIGHT, v - DECAY_STEP);
            if (v < DEFAULT_WEIGHT) return Math.min(DEFAULT_WEIGHT, v + DECAY_STEP);
            return v;
        });
    }

    /**
     * Get a brief description for the system prompt.
     * Show only the top 5 weighted features so the LLM knows what the AI considers important.
     */
    public String summarizeForPrompt() {
        if (featureWeights.isEmpty() || learnCount.get() < 3) {
            return "";  // Do not inject context before enough data has been learned
        }
        StringBuilder sb = new StringBuilder();
        featureWeights.entrySet().stream()
                .filter(e -> e.getValue() >= IMPORTANT_THRESHOLD)
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> {
                    if (sb.length() > 0) sb.append(", ");
                    // Extract the feature's subject (remove the tool-name prefix)
                    String feature = e.getKey();
                    int colonIdx = feature.indexOf(':');
                    if (colonIdx >= 0 && colonIdx < feature.length() - 1) {
                        sb.append(feature.substring(colonIdx + 1));
                    } else {
                        sb.append(feature);
                    }
                });
        if (sb.length() == 0) return "";
        return ContextMessages.text("importance.learned") + sb;
    }

    public int learnCount() { return learnCount.get(); }
    public int featureCount() { return featureWeights.size(); }

    // ─── Persistence support ───

    /**
     * Export learned feature weights for persistence.
     */
    public Map<String, Float> exportWeights() {
        return new ConcurrentHashMap<>(featureWeights);
    }

    /**
     * Import persisted feature weights, replacing all current data.
     */
    public void importWeights(Map<String, Float> weights, int savedLearnCount) {
        featureWeights.clear();
        if (weights != null) {
            weights.forEach((feature, weight) -> {
                if (feature != null && !feature.isBlank()
                        && weight != null && Float.isFinite(weight)) {
                    featureWeights.put(feature, clamp(weight));
                }
            });
        }
        learnCount.set(Math.max(0, savedLearnCount));
    }

    private static float clamp(float v) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, v));
    }
}
