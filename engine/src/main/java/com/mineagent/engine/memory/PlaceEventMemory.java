package com.mineagent.engine.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PEM (Place Event Memory) — records What-Where-When.
 *
 * <p>Inspired by Place Event Memory in Mr.Steve (ICLR 2025).
 * Addresses aimless wandering and repeated exploration:
 *
 * <ul>
 *   <li><b>Record</b>: where resources were seen (cows, iron ore, trees)</li>
 *   <li><b>Record</b>: where events occurred (creeper explosions, diamond discoveries)</li>
 *   <li><b>Retrieve</b>: recall where a needed resource was last seen</li>
 *   <li><b>Avoid repetition</b>: do not repeatedly search explored areas</li>
 * </ul>
 *
 * <p><b>Improvements:</b>
 * <ul>
 *   <li>Deduplication: keep only the latest record for a subject at the same location</li>
 *   <li>Importance scoring: evict by importance, preferring to retain rare resources</li>
 *   <li>Index optimization: direct Map access avoids rebuilding the entire index</li>
 *   <li>POI verification: mark resource locations that are no longer valid</li>
 * </ul>
 *
 * <p>Thread safety: all reads and writes use ConcurrentHashMap.
 */
public class PlaceEventMemory {

    /**
     * A location event record.
     *
     * @param type event type (resource/entity/danger/structure/loot)
     * @param subject subject (such as "cow", "iron_ore", "creeper_explosion", "village")
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param dimension dimension (overworld/nether/end)
     * @param timestamp game time in ticks
     * @param note optional note (may be null)
     * @param importance importance score (0.0-1.0)
     * @param visitCount visit/reinforcement count
     * @param verified whether the subject is verified to still exist
     */
    public record PlaceEvent(
            String type,
            String subject,
            int x, int y, int z,
            String dimension,
            long timestamp,
            String note,
            float importance,
            int visitCount,
            boolean verified
    ) {
        /** Create a reinforced copy */
        PlaceEvent reinforced(long newTimestamp) {
            return new PlaceEvent(type, subject, x, y, z, dimension, newTimestamp, note,
                    Math.min(1.0f, importance + 0.05f), visitCount + 1, true);
        }

        /** Mark as invalid */
        PlaceEvent invalidated() {
            return new PlaceEvent(type, subject, x, y, z, dimension, timestamp, note,
                    importance * 0.3f, visitCount, false);
        }

        /** Get the location key */
        String locationKey() {
            return subject.toLowerCase(Locale.ROOT) + ":" + x + "," + z
                    + ":" + normalizeDimension(dimension);
        }
    }

    private final List<PlaceEvent> events = Collections.synchronizedList(new ArrayList<>());

    /** Index by subject for faster retrieval. */
    private final Map<String, List<PlaceEvent>> bySubject = new ConcurrentHashMap<>();

    /** Location deduplication index: locationKey -> PlaceEvent */
    private final Map<String, PlaceEvent> locationIndex = new ConcurrentHashMap<>();

    /** FoV coverage: track explored chunk coordinates to avoid repeated scans. */
    private final Set<Long> exploredChunks = ConcurrentHashMap.newKeySet();

    /** Maximum number of memory entries */
    private static final int MAX_EVENTS = 500;

    /**
     * Calculate a memory's importance score
     */
    private float calculateImportance(String type, String subject, String note) {
        float score = 0.5f; // Base score

        // Danger memories have high importance
        if ("danger".equals(type)) score += 0.35f;
        if ("loot".equals(type)) score += 0.25f;
        if ("structure".equals(type)) score += 0.15f;

        // Rare-resource bonus
        String lower = (subject + " " + (note != null ? note : "")).toLowerCase();
        if (lower.contains("diamond") || lower.contains("emerald") ||
            lower.contains("ancient") || lower.contains("netherite") ||
            lower.contains("elytra") || lower.contains("beacon")) {
            score += 0.25f;
        } else if (lower.contains("iron") || lower.contains("gold") ||
                   lower.contains("redstone") || lower.contains("lapis") ||
                   lower.contains("village") || lower.contains("chest")) {
            score += 0.15f;
        }

        return Math.min(1.0f, score);
    }

    /**
     * Record a location event with deduplication.
     */
    public void remember(String type, String subject,
                          int x, int y, int z, String dimension,
                          long timestamp, String note) {
        if (subject == null || subject.isBlank()) return;
        String normalizedDimension = normalizeDimension(dimension);
        String locKey = subject.toLowerCase(Locale.ROOT) + ":" + x + "," + z
                + ":" + normalizedDimension;

        // Check for an existing memory of the same subject at the same location
        PlaceEvent existing = locationIndex.get(locKey);
        if (existing != null) {
            // Update existing memory: increase visit count and refresh importance
            float newImportance = Math.max(existing.importance(),
                    calculateImportance(type, subject, note));
            PlaceEvent updated = new PlaceEvent(type, subject, x, y, z, normalizedDimension,
                    timestamp, note, newImportance, existing.visitCount() + 1, true);

            // Remove the old record and add the updated one
            removeFromIndices(existing);
            addToIndices(updated);
            return;
        }

        // Create a new memory
        float importance = calculateImportance(type, subject, note);
        PlaceEvent e = new PlaceEvent(type, subject, x, y, z, normalizedDimension,
                timestamp, note, importance, 1, true);
        addToIndices(e);

        // Importance-based eviction: remove the least important entry when over capacity
        while (events.size() > MAX_EVENTS) {
            PlaceEvent leastImportant = findLeastImportant();
            if (leastImportant != null) {
                removeFromIndices(leastImportant);
            } else {
                break;
            }
        }
    }

    /** Add to indexes (both reads and writes of bySubject lists must use synchronized (list)) */
    private void addToIndices(PlaceEvent e) {
        events.add(e);
        List<PlaceEvent> list = bySubject.computeIfAbsent(e.subject().toLowerCase(Locale.ROOT),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(e);
        }
        locationIndex.put(e.locationKey(), e);
    }

    /** Remove from indexes */
    private void removeFromIndices(PlaceEvent e) {
        events.remove(e);
        locationIndex.remove(e.locationKey());
        List<PlaceEvent> list = bySubject.get(e.subject().toLowerCase(Locale.ROOT));
        if (list != null) {
            synchronized (list) {
                list.remove(e);
                if (list.isEmpty()) {
                    bySubject.remove(e.subject().toLowerCase(Locale.ROOT), list);
                }
            }
        }
    }

    /** Find the least important memory */
    private PlaceEvent findLeastImportant() {
        if (events.isEmpty()) return null;

        PlaceEvent least = null;
        float minScore = Float.MAX_VALUE;

        synchronized (events) {
            for (PlaceEvent e : events) {
                // Combined score: importance * 0.5 + visits * 0.2 + time decay * 0.2 + verification * 0.1
                long age = System.currentTimeMillis() - e.timestamp();
                float timeFactor = Math.max(0.1f, 1.0f - age / 7200000f); // Two-hour decay
                float verifiedBonus = e.verified() ? 0.1f : 0f;
                float score = e.importance() * 0.5f +
                             Math.min(e.visitCount() / 10f, 1.0f) * 0.2f +
                             timeFactor * 0.2f +
                             verifiedBonus;

                if (score < minScore) {
                    minScore = score;
                    least = e;
                }
            }
        }
        return least;
    }

    /**
     * Invalidate a resource at a location (for example, after it is mined).
     */
    public void invalidate(String subject, int x, int y, int z, String dimension) {
        if (subject == null || subject.isBlank()) return;
        String locKey = subject.toLowerCase(Locale.ROOT) + ":" + x + "," + z
                + ":" + normalizeDimension(dimension);
        PlaceEvent existing = locationIndex.get(locKey);
        if (existing != null) {
            PlaceEvent invalidated = existing.invalidated();
            removeFromIndices(existing);
            addToIndices(invalidated);
        }
    }

    /**
     * Find the most recently observed location for a subject.
     *
     * @param subject resource name (such as "iron_ore", "cow", "village")
     * @return latest PlaceEvent, or null
     */
    public PlaceEvent findLastSeen(String subject) {
        if (subject == null || subject.isBlank()) return null;
        List<PlaceEvent> list = bySubject.get(subject.toLowerCase(Locale.ROOT));
        if (list == null || list.isEmpty()) return null;
        synchronized (list) {
            // Filter invalid entries and return the latest verified record
            for (int i = list.size() - 1; i >= 0; i--) {
                PlaceEvent e = list.get(i);
                if (e.verified()) return e;
            }
            return list.get(list.size() - 1); // If all are invalid, return the latest record
        }
    }

    /**
     * Find all observed locations for a subject in a dimension.
     */
    public List<PlaceEvent> findAll(String subject, String dimension) {
        if (subject == null || subject.isBlank()) return Collections.emptyList();
        List<PlaceEvent> list = bySubject.get(subject.toLowerCase(Locale.ROOT));
        if (list == null) return Collections.emptyList();
        String normalizedDimension = normalizeDimension(dimension);
        List<PlaceEvent> result = new ArrayList<>();
        synchronized (list) {
            for (PlaceEvent e : list) {
                if (e.dimension().equals(normalizedDimension) && e.verified()) {
                    result.add(e);
                }
            }
        }
        // Sort by importance
        result.sort((a, b) -> Float.compare(b.importance(), a.importance()));
        return result;
    }

    /**
     * Mark a chunk as explored.
     */
    public void markExplored(int chunkX, int chunkZ) {
        exploredChunks.add(chunkKey(chunkX, chunkZ));
    }

    /**
     * Check whether a chunk has been explored.
     */
    public boolean isExplored(int chunkX, int chunkZ) {
        return exploredChunks.contains(chunkKey(chunkX, chunkZ));
    }

    /**
     * Generate a memory summary for the LLM.
     * Return only recent, relevant memories to limit token use.
     */
    public String summarizeForPrompt() {
        return summarizeForPrompt("", 10);
    }

    /** Return only locations semantically related to the active objective. */
    public String summarizeForPrompt(String query, int limit) {
        if (limit <= 0) return "";
        if (events.isEmpty()) return "(No location memories yet)";
        String needle = query == null ? "" : query.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("## Location memories (recently seen resources/events)\n");

        // Group by subject and keep only the latest verified record for each
        Map<String, PlaceEvent> latest = new LinkedHashMap<>();
        synchronized (events) {
            for (PlaceEvent e : events) {
                if (e.verified()) {
                    latest.put(e.subject().toLowerCase(Locale.ROOT), e); // Later records replace earlier ones
                }
            }
        }

        List<ScoredPlace> sorted = latest.values().stream()
                .map(event -> new ScoredPlace(event, placeScore(needle, event)))
                .filter(value -> needle.isBlank() || value.score() > 0.0)
                .sorted(Comparator.comparingDouble(ScoredPlace::score).reversed()
                        .thenComparing(value -> value.event().timestamp(),
                                Comparator.reverseOrder()))
                .toList();
        if (sorted.isEmpty()) return "";

        int shown = 0;
        for (ScoredPlace value : sorted) {
            if (shown >= Math.min(10, limit)) break;
            PlaceEvent e = value.event();
            sb.append("- ").append(e.subject())
              .append(" at (").append(e.x()).append(",").append(e.y()).append(",").append(e.z()).append(")")
              .append(" [").append(e.dimension()).append("]");
            if (e.note() != null && !e.note().isBlank()) {
                sb.append(" — ").append(e.note());
            }
            sb.append("\n");
            shown++;
        }

        // Number of explored chunks
        sb.append("(Explored ").append(exploredChunks.size()).append(" chunks)\n");

        return sb.toString();
    }

    private record ScoredPlace(PlaceEvent event, double score) {}

    private static double placeScore(String query, PlaceEvent event) {
        double semantic = query.isBlank() ? 0.25 : TextSimilarity.score(query,
                event.type() + " " + event.subject() + " "
                        + (event.note() == null ? "" : event.note()));
        if (!query.isBlank() && semantic <= 0.0) return 0.0;
        return semantic + event.importance() * 0.20
                + Math.min(0.08, event.visitCount() * 0.01);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Clear all memories (for tests or resets) */
    public void clear() {
        events.clear();
        bySubject.clear();
        locationIndex.clear();
        exploredChunks.clear();
    }

    // ─── Persistence support ───

    /**
     * Export all memory events for persistence.
     */
    public List<PlaceEvent> exportAll() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /**
     * Import persisted memory events, replacing all current data.
     */
    public void importAll(List<PlaceEvent> imported) {
        events.clear();
        bySubject.clear();
        locationIndex.clear();
        if (imported == null) return;
        for (PlaceEvent e : imported) {
            if (e != null && e.subject() != null && e.dimension() != null) {
                String dimension = normalizeDimension(e.dimension());
                float importance = Float.isFinite(e.importance())
                        ? Math.max(0.0f, Math.min(1.0f, e.importance())) : 0.5f;
                addToIndices(new PlaceEvent(
                        e.type() != null ? e.type() : "event", e.subject(),
                        e.x(), e.y(), e.z(), dimension, e.timestamp(), e.note(),
                        importance, Math.max(0, e.visitCount()), e.verified()));
            }
        }
        // If over capacity after import, evict by importance
        while (events.size() > MAX_EVENTS) {
            PlaceEvent leastImportant = findLeastImportant();
            if (leastImportant != null) {
                removeFromIndices(leastImportant);
            } else {
                break;
            }
        }
    }

    /** Get the number of memory entries */
    public int size() {
        return events.size();
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank() || "overworld".equals(dimension)) {
            return "minecraft:overworld";
        }
        if ("nether".equals(dimension)) return "minecraft:the_nether";
        if ("end".equals(dimension)) return "minecraft:the_end";
        return dimension;
    }
}
