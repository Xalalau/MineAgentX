package com.mineagent.engine.memory;

import com.mineagent.engine.i18n.ContextMessages;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reflection system — extracts lessons from failures to guide future decisions.
 *
 * <p>Inspired by self-improvement in Reflexion (NeurIPS 2023) and Voyager:
 * rather than merely record failure, analyze why it happened and produce actionable lessons.
 *
 * <p>Three reflection levels:
 * <ul>
 *   <li><b>Micro</b>: individual tool-call failure → parameter correction suggestions</li>
 *   <li><b>Meso</b>: task failure → strategy adjustment suggestions</li>
 *   <li><b>Macro</b>: repeated behavioral failure → decision-style adjustments</li>
 * </ul>
 *
 * <p><b>Improvements:</b>
 * <ul>
 *   <li>Replace hardcoded keywords with general pattern matching</li>
 *   <li>Classify failures by error structure rather than specific vocabulary</li>
 *   <li>Generate targeted lessons dynamically from error context</li>
 * </ul>
 */
public class ReflectionSystem {

    /**
     * A reflection record.
     *
     * @param level reflection level (micro/meso/macro)
     * @param trigger cause (failed tool or task description)
     * @param context summary of the context at failure
     * @param lesson extracted lesson
     * @param timestamp record time
     * @param applicability situations where this lesson applies
     */
    public record Reflection(
            String level,
            String trigger,
            String context,
            String lesson,
            long timestamp,
            String applicability
    ) {}

    /** Default maximum number of reflections */
    private static final int DEFAULT_MAX_REFLECTIONS = 30;

    private final Deque<Reflection> reflections = new ArrayDeque<>();
    private final ConcurrentMap<String, Integer> failurePatterns = new ConcurrentHashMap<>();
    private final int maxReflections;

    public ReflectionSystem() {
        this(DEFAULT_MAX_REFLECTIONS);
    }

    public ReflectionSystem(int maxReflections) {
        this.maxReflections = maxReflections;
    }

    /**
     * Record a tool-call failure.
     * Improvement: general pattern matching rather than hardcoded keywords.
     */
    public void recordToolFailure(String toolName, String params, String error, String context) {
        String pattern = categorizeFailure(toolName, error);
        failurePatterns.merge(pattern, 1, Integer::sum);

        String lesson = generateMicroLesson(toolName, error, params);
        String trigger = toolName + "(" + truncate(params, 40) + ")";

        addReflection(new Reflection(
                "micro",
                trigger,
                truncate(context, 60),
                lesson,
                System.currentTimeMillis(),
                toolName
        ));
    }

    /**
     * Record a task failure.
     */
    public void recordTaskFailure(String taskDescription, String reason, String context) {
        String lesson = generateMesoLesson(taskDescription, reason);

        addReflection(new Reflection(
                "meso",
                taskDescription,
                truncate(context, 60),
                lesson,
                System.currentTimeMillis(),
                "task"
        ));
    }

    /**
     * Record a behavioral failure pattern (such as repeated failure on one task type).
     */
    public void recordPatternFailure(String pattern, int failureCount, String context) {
        String lesson = ContextMessages.text("reflection.pattern", pattern, failureCount);

        addReflection(new Reflection(
                "macro",
                pattern,
                truncate(context, 60),
                lesson,
                System.currentTimeMillis(),
                "pattern"
        ));
    }

    // ─── AgentLoop compatibility methods ───

    /**
     * Record a failure (called by AgentLoop)
     * @param taskDesc task description
     * @param toolName tool name
     * @param failReason failure reason
     */
    public void recordFailure(String taskDesc, String toolName, String failReason) {
        recordToolFailure(toolName, taskDesc, failReason, taskDesc);
    }

    /**
     * Record a failed task (called by AgentLoop)
     * @param taskDesc task description
     * @param attemptedPlan attempted plan
     * @param failReason failure reason
     */
    public void recordFailedTask(String taskDesc, String attemptedPlan, String failReason) {
        recordTaskFailure(taskDesc, failReason, attemptedPlan);
    }

    /**
     * Generate a reflection summary for the prompt (called by AgentLoop).
     * Return recent lessons without requiring a match; the previous empty-string matching
     * prevented micro/meso lessons from ever entering the prompt.
     * @return reflection summary text
     */
    public String summarizeForPrompt() {
        List<Reflection> recent;
        synchronized (reflections) {
            recent = new ArrayList<>(reflections);
        }
        if (recent.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(ContextMessages.text("reflection.heading"));
        // Latest 3 lessons (newest last)
        int start = Math.max(0, recent.size() - 3);
        for (int i = start; i < recent.size(); i++) {
            sb.append("- ").append(recent.get(i).lesson()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Format failure memories for the prompt (called by AgentLoop)
     * @param currentTask current task description
     * @return formatted failure memory text
     */
    public String formatFailuresForPrompt(String currentTask) {
        List<Reflection> relevant = recallRelevantFailures(currentTask, "");
        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(ContextMessages.text("reflection.failures"));
        for (Reflection r : relevant) {
            sb.append(ContextMessages.text("reflection.task")).append(truncate(r.trigger(), 30))
              .append(ContextMessages.text("reflection.lesson")).append(r.lesson()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Add a reflection record.
     * Synchronization: exportAll (persistence thread) may run concurrently with this method.
     */
    private void addReflection(Reflection r) {
        synchronized (reflections) {
            reflections.addLast(r);
            while (reflections.size() > maxReflections) {
                reflections.pollFirst();
            }
        }
    }

    /**
     * Retrieve reflections relevant to the current task.
     * Improvement: flexible matching without hardcoded keywords.
     */
    public List<Reflection> recallRelevantFailures(String taskDescription, String toolName) {
        List<Reflection> relevant = new ArrayList<>();
        String lowerTask = taskDescription != null ? taskDescription.toLowerCase() : "";

        synchronized (reflections) {
            for (Reflection r : reflections) {
                // Null safety: deserialization or exceptional paths may produce records with missing fields
                if (r.applicability() == null || r.trigger() == null || r.level() == null) {
                    continue;
                }
                // Failures involving the same tool type
                if (r.applicability().equals(toolName)) {
                    relevant.add(r);
                }
                // Failure pattern matching (general matching rather than hardcoded keywords)
                else if (isContextMatch(lowerTask, r.trigger().toLowerCase())) {
                    relevant.add(r);
                }
                // Macro-level lessons are always relevant
                else if ("macro".equals(r.level())) {
                    relevant.add(r);
                }
            }
        }

        // Return at most the latest 3
        return relevant.size() > 3 ?
                new ArrayList<>(relevant.subList(relevant.size() - 3, relevant.size())) :
                relevant;
    }

    /**
     * Generate a reflection summary for the prompt.
     */
    public String summarizeForPrompt(String currentTask) {
        List<Reflection> relevant = recallRelevantFailures(currentTask, "");
        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(ContextMessages.text("reflection.heading"));
        for (Reflection r : relevant) {
            sb.append("- ").append(r.lesson()).append("\n");
        }
        return sb.toString();
    }

    // ─── Private methods ───

    /**
     * Determine whether contexts match (general pattern matching, not hardcoded keywords)
     */
    private boolean isContextMatch(String task, String trigger) {
        // Extract shared words (length >= 3)
        String[] taskWords = task.split("\\s+");
        for (String word : taskWords) {
            if (word.length() >= 3 && trigger.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classify failure patterns using general patterns rather than hardcoded keywords.
     * Based on error structure rather than specific vocabulary.
     */
    private String categorizeFailure(String toolName, String error) {
        if (error == null) return toolName + ":unknown";
        String lower = error.toLowerCase();

        // General error classification (by error type, not keywords)
        // 1. Parameter/validation errors
        if (lower.contains("invalid") || lower.contains("illegal") ||
            lower.contains("missing") || lower.contains("required") ||
            lower.contains("null") || lower.contains("empty")) {
            return toolName + ":invalid_params";
        }
        // 2. Resource/object not found
        if (lower.contains("not found") || lower.contains("no such") ||
            lower.contains("doesn't exist") || lower.contains("cannot find") ||
            lower.contains("unable to locate")) {
            return toolName + ":not_found";
        }
        // 3. Permission/access errors
        if (lower.contains("cannot") || lower.contains("can't") ||
            lower.contains("denied") || lower.contains("forbidden") ||
            lower.contains("not allowed") || lower.contains("unreachable")) {
            return toolName + ":access_denied";
        }
        // 4. Timeout/performance issues
        if (lower.contains("timeout") || lower.contains("timed out") ||
            lower.contains("too slow") || lower.contains("expired")) {
            return toolName + ":timeout";
        }
        // 5. Unsatisfied state/conditions
        if (lower.contains("already") || lower.contains("not ready") ||
            lower.contains("busy") || lower.contains("locked") ||
            lower.contains("in use")) {
            return toolName + ":state_conflict";
        }

        return toolName + ":unknown";
    }

    /**
     * Generate a micro-level lesson with general advice based on error type.
     */
    private String generateMicroLesson(String toolName, String error, String params) {
        String lower = error != null ? error.toLowerCase() : "";

        // Parameter errors
        if (lower.contains("invalid") || lower.contains("illegal") ||
            lower.contains("missing") || lower.contains("required")) {
            return ContextMessages.text("reflection.invalid", toolName, truncate(params, 30));
        }
        // Not found
        if (lower.contains("not found") || lower.contains("no such") ||
            lower.contains("cannot find")) {
            return ContextMessages.text("reflection.notFound", toolName);
        }
        // Access/permission issues
        if (lower.contains("cannot") || lower.contains("can't") ||
            lower.contains("unreachable") || lower.contains("denied")) {
            return ContextMessages.text("reflection.access", toolName);
        }
        // Timeout
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return ContextMessages.text("reflection.timeout", toolName);
        }
        // State conflict
        if (lower.contains("already") || lower.contains("busy") ||
            lower.contains("locked")) {
            return ContextMessages.text("reflection.conflict");
        }

        // General advice
        return ContextMessages.text("reflection.toolFailed", toolName, truncate(error, 40));
    }

    /**
     * Generate a meso-level lesson.
     */
    private String generateMesoLesson(String taskDescription, String reason) {
        String lowerReason = reason != null ? reason.toLowerCase() : "";

        // Task decomposition issues
        if (lowerReason.contains("too complex") || lowerReason.contains("too big") ||
            lowerReason.contains("overwhelming")) {
            return ContextMessages.text("reflection.complex", truncate(taskDescription, 25));
        }
        // Insufficient resources
        if (lowerReason.contains("lack") || lowerReason.contains("insufficient") ||
            lowerReason.contains("not enough") || lowerReason.contains("missing")) {
            return ContextMessages.text("reflection.resources");
        }
        // Path/navigation issues
        if (lowerReason.contains("stuck") || lowerReason.contains("cannot reach") ||
            lowerReason.contains("blocked") || lowerReason.contains("unreachable")) {
            return ContextMessages.text("reflection.navigation");
        }
        // Environmental/external factors
        if (lowerReason.contains("danger") || lowerReason.contains("hostile") ||
            lowerReason.contains("threat")) {
            return ContextMessages.text("reflection.danger");
        }

        // General advice
        return ContextMessages.text("reflection.taskFailed", truncate(taskDescription, 25), truncate(reason, 40));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** Get the number of reflections */
    public int size() {
        synchronized (reflections) {
            return reflections.size();
        }
    }

    /** Clear all reflections */
    public void clear() {
        synchronized (reflections) {
            reflections.clear();
        }
        failurePatterns.clear();
    }

    // ─── Persistence support ───

    /**
     * Export all reflections for persistence.
     */
    public List<Reflection> exportAll() {
        synchronized (reflections) {
            return new ArrayList<>(reflections);
        }
    }

    /**
     * Export failure pattern counts for persistence.
     */
    public ConcurrentMap<String, Integer> exportPatterns() {
        return new ConcurrentHashMap<>(failurePatterns);
    }

    /**
     * Import persisted reflections and failure patterns, replacing all current data.
     */
    public void importAll(List<Reflection> imported, Map<String, Integer> patterns) {
        synchronized (reflections) {
            reflections.clear();
            if (imported != null) {
                for (Reflection r : imported) {
                    // Filter records with missing fields (older files or manual edits may omit components),
                    // preventing null trigger/applicability values from causing an NPE during recall
                    if (r != null && r.lesson() != null && r.trigger() != null
                            && r.applicability() != null && r.level() != null) {
                        reflections.addLast(r);
                    }
                }
                while (reflections.size() > maxReflections) {
                    reflections.pollFirst();
                }
            }
        }
        failurePatterns.clear();
        if (patterns != null) {
            failurePatterns.putAll(patterns);
        }
    }
}
