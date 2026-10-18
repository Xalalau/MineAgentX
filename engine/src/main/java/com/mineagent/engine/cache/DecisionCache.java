package com.mineagent.engine.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Decision cache — reuses LLM decisions in similar situations.
 *
 * <p>Purpose: reduce LLM calls and latency.
 * When the AI receives a similar command in a similar state, reuse the previous decision.
 *
 * <p>Key design: quantized position + health tier + nearest entity type + command hash
 * Value: LLM decision (assistant message content + tool_calls)
 *
 * <p>A 30-second TTL avoids unreasonable behavior caused by stale decisions.
 */
public class DecisionCache {

    /** A cached decision. */
    private record CacheEntry(
            String decisionContent,
            String toolCallsJson,
            long expiresAt
    ) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Cache TTL in milliseconds.
     *  Reduced from 30 seconds to 10 seconds to lower the risk of decisions outliving their context.
     *  Ten seconds covers repetitive actions such as continuous mining without
     *  repeating old decisions after the player has left. */
    private static final long TTL_MS = 10_000;

    /** Maximum number of cache entries. */
    private static final int MAX_SIZE = 500;

    /**
     * Generate a cache key.
     *
     * @param posX AI player X coordinate (quantized to 4 blocks)
     * @param posY AI player Y coordinate (quantized to 4 blocks)
     * @param posZ AI player Z coordinate (quantized to 4 blocks)
     * @param healthTier health tier (0-4 / 5-9 / 10-14 / 15-20)
     * @param hungerTier hunger tier
     * @param nearestEntity nearest entity type (or null)
     * @param command player command (or null)
     * @return cache key
     */
    public static String buildKey(int posX, int posY, int posZ,
                                    int healthTier, int hungerTier,
                                    String nearestEntity, String command) {
        // Quantize position to 4-block precision
        int qx = posX >> 2; // posX / 4
        int qy = posY >> 2;
        int qz = posZ >> 2;
        return qx + "," + qy + "," + qz + "|"
                + healthTier + "|" + hungerTier + "|"
                + (nearestEntity != null ? nearestEntity : "-") + "|"
                + (command != null ? command.hashCode() : "-");
    }

    /**
     * Look up a cached decision.
     *
     * @return cached decision, or null on a miss or expiration
     */
    public CacheEntry get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) cache.remove(key);
            return null;
        }
        return entry;
    }

    /**
     * Look up cached decision text.
     */
    public String getDecision(String key) {
        CacheEntry entry = get(key);
        return entry != null ? entry.decisionContent() : null;
    }

    /**
     * Look up cached tool-call JSON.
     */
    public String getToolCalls(String key) {
        CacheEntry entry = get(key);
        return entry != null ? entry.toolCallsJson() : null;
    }

    /**
     * Store a decision in the cache.
     */
    public void put(String key, String decisionContent, String toolCallsJson) {
        // Enforce capacity
        if (cache.size() >= MAX_SIZE) {
            // Simple cleanup: remove expired entries
            cache.entrySet().removeIf(e -> e.getValue().isExpired());
            // If still too large, clear half the entries
            if (cache.size() >= MAX_SIZE) {
                int half = cache.size() / 2;
                var iter = cache.keySet().iterator();
                for (int i = 0; i < half && iter.hasNext(); i++) {
                    iter.next();
                    iter.remove();
                }
            }
        }
        cache.put(key, new CacheEntry(
                decisionContent, toolCallsJson,
                System.currentTimeMillis() + TTL_MS));
    }

    /**
     * Clear the cache.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Current cache size.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Remove expired entries.
     */
    public void cleanExpired() {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
