package com.mineagent.engine.memory;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cognitive Map — hippocampus-inspired spatial memory.
 *
 * <p>Core capabilities:
 * <ul>
 *   <li><b>Spatial memory</b>: store POI locations by category (resource:iron_ore / hazard:creeper / structure:village)</li>
 *   <li><b>Category retrieval</b>: exact category ("resource:iron_ore") and prefix ("resource") queries</li>
 *   <li><b>Proximity retrieval</b>: find memories within a given radius</li>
 *   <li><b>Semantic summary</b>: compact spatial memory text for LLM prompts</li>
 * </ul>
 *
 * <p><b>Improvements:</b>
 * <ul>
 *   <li>Deduplication: reinforce nearby POIs of the same category instead of duplicating memories</li>
 *   <li>Importance scoring: prefer retaining rare resources and dangerous locations during eviction</li>
 *   <li>POI verification: invalidate depleted resources so stale information does not mislead</li>
 *   <li>Visit tracking: combine importance, visit frequency, and recency during eviction</li>
 * </ul>
 *
 * <p>Neuroscience basis: hippocampal place cells encode spatial locations,
 * while grid cells provide a metric framework. This map combines both
 * to support spatial reasoning ("home is north", "the mine is below").
 *
 * <p>Thread safety: all reads and writes use ConcurrentHashMap.
 */
public class CognitiveMap {

    /** Default maximum number of POIs */
    private static final int DEFAULT_MAX_POIS = 200;

    /** Deduplication radius in blocks: same-category POIs within this radius count as one place */
    private static final int DEDUP_RADIUS = 16;

    /**
     * A point of interest (POI).
     *
     * @param category category (such as "resource:iron_ore", "hazard:creeper", "structure:village")
     * @param label display name (such as "iron_ore (resource)")
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param timestamp first discovery time in milliseconds
     * @param importance importance score (0.0-1.0)
     * @param visitCount reinforcement/visit count
     * @param verified whether the POI is verified to still exist
     * @param lastVisitTime latest reinforcement time in milliseconds
     */
    public record PointOfInterest(
            String category,
            String label,
            int x, int y, int z,
            String dimension,
            long timestamp,
            float importance,
            int visitCount,
            boolean verified,
            long lastVisitTime
    ) {
        /** Create a reinforced copy */
        PointOfInterest reinforced(long now) {
            return new PointOfInterest(category, label, x, y, z, dimension, timestamp,
                    Math.min(1.0f, importance + 0.05f), visitCount + 1, true, now);
        }

        /** Mark as invalid */
        PointOfInterest invalidated() {
            return new PointOfInterest(category, label, x, y, z, dimension, timestamp,
                    importance * 0.3f, visitCount, false, lastVisitTime);
        }
    }

    /** POI storage: key -> PointOfInterest */
    private final Map<String, PointOfInterest> pois = new ConcurrentHashMap<>();
    private final int maxPOIs;

    public CognitiveMap() {
        this(DEFAULT_MAX_POIS);
    }

    public CognitiveMap(int maxPOIs) {
        if (maxPOIs <= 0) throw new IllegalArgumentException("maxPOIs must be positive");
        this.maxPOIs = maxPOIs;
    }

    /**
     * Calculate a POI's importance score.
     * Based on category and label: hazards > rare resources > common resources > entities.
     */
    private float calculateImportance(String category, String label) {
        float score = 0.5f; // Base score

        String lowerCat = category != null ? category.toLowerCase(Locale.ROOT) : "";
        String lowerLabel = label != null ? label.toLowerCase(Locale.ROOT) : "";
        String combined = lowerCat + " " + lowerLabel;

        // Base score by category
        if (lowerCat.startsWith("hazard")) score += 0.3f;
        else if (lowerCat.startsWith("structure")) score += 0.15f;
        else if (lowerCat.startsWith("resource")) score += 0.1f;

        // Rarity bonus
        if (combined.contains("diamond") || combined.contains("emerald")
                || combined.contains("ancient") || combined.contains("netherite")) {
            score += 0.25f;
        } else if (combined.contains("iron") || combined.contains("gold")
                || combined.contains("village") || combined.contains("chest")
                || combined.contains("redstone") || combined.contains("lapis")) {
            score += 0.15f;
        }

        return Math.min(1.0f, score);
    }

    /**
     * Record a POI with deduplication.
     * Reinforce an existing same-category POI within <= 16 blocks instead of creating another.
     *
     * @param pos position
     * @param category category (such as "resource:iron_ore")
     * @param label display label
     * @param timestamp discovery time in milliseconds
     */
    public void recordPoi(BlockPos pos, String category, String label, long timestamp) {
        recordPoi(pos, category, label, "minecraft:overworld", timestamp);
    }

    public void recordPoi(BlockPos pos, String category, String label,
                          String dimension, long timestamp) {
        Objects.requireNonNull(pos, "pos");
        String cat = category != null ? category.toLowerCase(Locale.ROOT) : "event:unknown";
        String dim = normalizeDimension(dimension);

        // Deduplication: same category + short distance → reinforce the existing POI
        String existingKey = findNearbyKey(
                cat, dim, pos.getX(), pos.getZ(), DEDUP_RADIUS);
        if (existingKey != null) {
            PointOfInterest existing = pois.get(existingKey);
            if (existing != null) {
                pois.put(existingKey, existing.reinforced(timestamp));
            }
            return;
        }

        float importance = calculateImportance(cat, label);
        PointOfInterest poi = new PointOfInterest(cat, label,
                pos.getX(), pos.getY(), pos.getZ(),
                dim,
                timestamp, importance, 1, true, timestamp);
        pois.put(poiKey(cat, dim, pos.getX(), pos.getZ()), poi);

        evictIfNeeded();
    }

    /**
     * Find nearby memories (verified POIs only).
     *
     * @param x center X coordinate
     * @param z center Z coordinate
     * @param radius radius in blocks
     * @return POIs sorted by descending importance
     */
    public List<PointOfInterest> findNearby(int x, int z, int radius) {
        return findNearby(x, z, radius, null);
    }

    public List<PointOfInterest> findNearby(int x, int z, int radius, String dimension) {
        String dim = dimension == null ? null : normalizeDimension(dimension);
        long r2 = (long) radius * radius;
        List<PointOfInterest> result = new ArrayList<>();
        for (PointOfInterest poi : pois.values()) {
            if (!poi.verified()) continue;
            if (dim != null && !dim.equals(poi.dimension())) continue;
            long dx = poi.x() - x;
            long dz = poi.z() - z;
            if (dx * dx + dz * dz <= r2) {
                result.add(poi);
            }
        }
        result.sort((a, b) -> Float.compare(b.importance(), a.importance()));
        return result;
    }

    /**
     * Find memories by category (verified POIs only).
     * Supports exact matches ("resource:iron_ore") and prefixes ("resource" matches all resources).
     *
     * @param category category query
     * @return POIs sorted by descending importance
     */
    public List<PointOfInterest> findByCategory(String category) {
        return findByCategory(category, null);
    }

    public List<PointOfInterest> findByCategory(String category, String dimension) {
        String query = category != null ? category.toLowerCase(Locale.ROOT) : "";
        String dim = dimension == null ? null : normalizeDimension(dimension);
        List<PointOfInterest> result = new ArrayList<>();
        for (PointOfInterest poi : pois.values()) {
            if (!poi.verified()) continue;
            if (dim != null && !dim.equals(poi.dimension())) continue;
            String cat = poi.category();
            // Exact or prefix match ("resource" matches "resource:iron_ore")
            if (cat.equals(query) || cat.startsWith(query + ":")) {
                result.add(poi);
            }
        }
        result.sort((a, b) -> Float.compare(b.importance(), a.importance()));
        return result;
    }

    /**
     * Invalidate POIs near a position (for example, after a resource is mined).
     */
    public void invalidateNearby(int x, int z, int radius) {
        invalidateNearby(x, z, radius, null);
    }

    public void invalidateNearby(int x, int z, int radius, String dimension) {
        String dim = dimension == null ? null : normalizeDimension(dimension);
        long r2 = (long) radius * radius;
        for (Map.Entry<String, PointOfInterest> entry : pois.entrySet()) {
            PointOfInterest poi = entry.getValue();
            if (dim != null && !dim.equals(poi.dimension())) continue;
            long dx = poi.x() - x;
            long dz = poi.z() - z;
            if (dx * dx + dz * dz <= r2) {
                entry.setValue(poi.invalidated());
            }
        }
    }

    /**
     * Generate a spatial memory summary for the LLM prompt.
     * Group by category and show only verified, important POIs to limit token use.
     */
    public String summarizeForPrompt() {
        return summarizeForPrompt("", 8);
    }

    /** Return a bounded spatial-memory packet relevant to the active goal. */
    public String summarizeForPrompt(String query, int limit) {
        if (limit <= 0) return "";
        String needle = query == null ? "" : query.trim();
        List<ScoredPoi> verified = pois.values().stream()
                .filter(PointOfInterest::verified)
                .map(poi -> new ScoredPoi(poi, spatialScore(needle, poi)))
                .filter(value -> needle.isBlank() || value.score() > 0.0)
                .sorted(Comparator.comparingDouble(ScoredPoi::score).reversed()
                        .thenComparing(value -> value.poi().lastVisitTime(),
                                Comparator.reverseOrder()))
                .toList();
        if (verified.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Spatial memory\n");
        int shown = 0;
        Map<String, Integer> perTopCategory = new HashMap<>();
        for (ScoredPoi value : verified) {
            if (shown >= Math.min(8, limit)) break;
            PointOfInterest poi = value.poi();
            // Show at most 3 per broad category (resource/hazard/...) for diversity
            String topCat = poi.category().split(":")[0];
            int count = perTopCategory.getOrDefault(topCat, 0);
            if (count >= 2) continue;
            sb.append("- ").append(poi.label())
              .append(" at (").append(poi.x()).append(",")
              .append(poi.y()).append(",").append(poi.z()).append(")")
              .append(" [").append(poi.dimension()).append("]");
            if (poi.visitCount() > 2) {
                sb.append(" [confirmed x").append(poi.visitCount()).append("]");
            }
            sb.append("\n");
            perTopCategory.put(topCat, count + 1);
            shown++;
        }
        return sb.toString();
    }

    private record ScoredPoi(PointOfInterest poi, double score) {}

    private static double spatialScore(String query, PointOfInterest poi) {
        double semantic = query.isBlank() ? 0.25 : TextSimilarity.score(query,
                poi.category() + " " + poi.label());
        if (!query.isBlank() && semantic <= 0.0) return 0.0;
        return semantic + poi.importance() * 0.20
                + Math.min(0.08, poi.visitCount() * 0.01);
    }

    // ─── Persistence support ───

    /**
     * Export all POIs for persistence.
     */
    public List<PointOfInterest> exportAll() {
        return new ArrayList<>(pois.values());
    }

    /**
     * Import persisted POIs, replacing all current data.
     */
    public void importAll(List<PointOfInterest> imported) {
        pois.clear();
        if (imported == null) return;
        for (PointOfInterest poi : imported) {
            if (poi != null && poi.category() != null) {
                String dimension = normalizeDimension(poi.dimension());
                float importance = Float.isFinite(poi.importance())
                        ? Math.max(0.0f, Math.min(1.0f, poi.importance())) : 0.5f;
                PointOfInterest normalized = new PointOfInterest(
                        poi.category().toLowerCase(Locale.ROOT), poi.label(),
                        poi.x(), poi.y(), poi.z(), dimension, poi.timestamp(),
                        importance, Math.max(0, poi.visitCount()), poi.verified(),
                        poi.lastVisitTime());
                pois.put(poiKey(normalized.category(), normalized.dimension(),
                        normalized.x(), normalized.z()), normalized);
            }
        }
        evictIfNeeded();
    }

    // ─── Private methods ───

    /** Generate a POI storage key */
    private String poiKey(String category, String dimension, int x, int z) {
        return dimension + ":" + category + ":" + x + "," + z;
    }

    /** Find an existing same-category POI key within the radius */
    private String findNearbyKey(String category, String dimension,
                                 int x, int z, int radius) {
        long r2 = (long) radius * radius;
        for (Map.Entry<String, PointOfInterest> entry : pois.entrySet()) {
            PointOfInterest poi = entry.getValue();
            if (!poi.category().equals(category) || !poi.dimension().equals(dimension)) continue;
            long dx = poi.x() - x;
            long dz = poi.z() - z;
            if (dx * dx + dz * dz <= r2) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank() || "overworld".equals(dimension)) {
            return "minecraft:overworld";
        }
        if ("nether".equals(dimension)) return "minecraft:the_nether";
        if ("end".equals(dimension)) return "minecraft:the_end";
        return dimension;
    }

    /**
     * Evict POIs by combined score.
     * Score = importance*0.4 + visit frequency*0.2 + recency*0.2 + verification*0.2
     */
    private void evictIfNeeded() {
        while (pois.size() > maxPOIs) {
            String leastKey = null;
            float minScore = Float.MAX_VALUE;
            long now = System.currentTimeMillis();

            for (Map.Entry<String, PointOfInterest> entry : pois.entrySet()) {
                PointOfInterest poi = entry.getValue();
                long age = now - poi.lastVisitTime();
                float recencyFactor = Math.max(0.1f, 1.0f - age / 7200000f); // Two-hour decay
                float verifiedBonus = poi.verified() ? 0.2f : 0f;
                float score = poi.importance() * 0.4f
                        + Math.min(poi.visitCount() / 10f, 1.0f) * 0.2f
                        + recencyFactor * 0.2f
                        + verifiedBonus;

                if (score < minScore) {
                    minScore = score;
                    leastKey = entry.getKey();
                }
            }

            if (leastKey != null) {
                pois.remove(leastKey);
            } else {
                break;
            }
        }
    }

    /** Get the number of POIs */
    public int size() {
        return pois.size();
    }

    /** Clear all data */
    public void clear() {
        pois.clear();
    }
}
