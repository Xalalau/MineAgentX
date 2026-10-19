package com.mineagent.engine.construction;

import com.mineagent.api.construction.ConstructionDesign;
import com.mineagent.api.construction.ConstructionDesign.Point;
import java.util.*;

/** Deterministic evidence, not an architectural/site choice or a promise of elapsed time. */
public record SiteAssessment(String dimension, Point origin, int quarterTurns, Point size, long observedTick,
                             int sampledVolume, int freeVolume, int occupiedVolume, int unknown,
                             int trees, int fluids, int assetConflicts, int protectedConflicts,
                             int unsupportedFootprint, int accessibleBoundaryCells,
                             int placements, int excavations, int alreadySatisfied,
                             long breakTicks, int unknownBreakCosts, long effortUnits,
                             long approachDistance, Map<String, Integer> materials,
                             Map<String, Integer> deficit, Map<String, Integer> occupiedBlocks,
                             List<Finding> findings, boolean executable) {
    /** Null observation means unknown, unloaded, out of bounds, or outside allowed observation range. */
    @FunctionalInterface public interface WorldView { Observation observe(Point world); }
    public record Observation(String block, boolean replaceable, boolean tree, boolean fluid,
                              boolean asset, boolean protectedBlock, int breakTicks) {
        public Observation { ConstructionDesign.resourceId(block); if (breakTicks < -1) throw new IllegalArgumentException("break_ticks"); }
        public boolean air() { return block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air"); }
    }
    public record Finding(Point position, String block, String kind) {}
    public static SiteAssessment inspect(ConstructionDesign d, WorldView world, Point observer, long tick) {
        return inspect(d, world, observer, tick, Map.of());
    }
    /** Carried counts are in exact block IDs; storage evidence is reported separately, never assumed carried. */
    public static SiteAssessment inspect(ConstructionDesign d, WorldView world, Point observer, long tick,
                                         Map<String, Integer> carried) {
        var observed = new HashMap<Point, Observation>();
        int volume = 0, free = 0, occupied = 0, unknown = 0, trees = 0, fluids = 0;
        int assets = 0, protectedCount = 0, unsupported = 0, boundary = 0;
        var histogram = new TreeMap<String, Integer>();
        var findings = new ArrayList<Finding>();
        for (int x = 0; x < d.size().x(); x++) for (int z = 0; z < d.size().z(); z++) {
            for (int y = 0; y < d.size().y(); y++) {
                Point p = d.world(new Point(x, y, z));
                Observation o = world.observe(p); volume++;
                observed.put(p, o);
                if (o == null) { unknown++; continue; }
                if (o.air() || o.replaceable && !o.fluid) free++; else occupied++;
                if (!o.air()) histogram.merge(o.block, 1, Integer::sum);
                if (o.tree) trees++;
                if (o.fluid) fluids++;
                if ((o.asset || o.protectedBlock || o.tree || o.fluid) && findings.size() < 32)
                    findings.add(new Finding(p, o.block, o.asset ? "existing_asset" : o.protectedBlock ? "protected" : o.fluid ? "fluid" : "tree"));
            }
            Observation below = world.observe(d.world(new Point(x, -1, z)));
            if (below == null) unknown++;
            if (below == null || below.air() || below.replaceable || below.fluid) unsupported++;
            if (x == 0 || z == 0 || x == d.size().x() - 1 || z == d.size().z() - 1) {
                Observation low = observed.get(d.world(new Point(x, 0, z)));
                Observation high = d.size().y() > 1 ? observed.get(d.world(new Point(x, 1, z)))
                        : world.observe(d.world(new Point(x, 1, z)));
                if (low != null && high != null && (low.air() || low.replaceable) && !low.fluid
                        && (high.air() || high.replaceable) && !high.fluid) boundary++;
            }
        }
        int placements = 0, excavations = 0, satisfied = 0, unknownCosts = 0;
        long breakTicks = 0, approach = Long.MAX_VALUE;
        var materials = new TreeMap<String, Integer>();
        for (var cell : d.cells()) {
            Point p = d.world(cell.position());
            approach = Math.min(approach, Math.abs((long) p.x() - observer.x())
                    + Math.abs((long) p.y() - observer.y()) + Math.abs((long) p.z() - observer.z()));
            Observation o = observed.get(p);
            boolean matches = o != null && (cell.clear() ? o.air() : cell.block().equals(o.block));
            if (matches) { satisfied++; continue; }
            if (o != null && o.protectedBlock) protectedCount++;
            if (!cell.clear()) { placements++; materials.merge(cell.block(), 1, Integer::sum); }
            // Even replaceable vegetation in explicit clear cells requires physical removal.
            boolean clear = o != null && !o.air() && (cell.clear() || !o.replaceable || o.fluid);
            if (clear) {
                excavations++;
                if (o.asset) assets++;
                if (o.breakTicks < 0) unknownCosts++; else breakTicks += o.breakTicks;
            }
        }
        var deficit = new TreeMap<String, Integer>();
        materials.forEach((block, needed) -> deficit.put(block, Math.max(0, needed - Math.max(0, carried.getOrDefault(block, 0)))));
        return new SiteAssessment(d.dimension(), d.origin(), d.quarterTurns(), d.size(), Math.max(0, tick),
                volume, free, occupied, unknown, trees, fluids, assets, protectedCount, unsupported, boundary,
                placements, excavations, satisfied, breakTicks, unknownCosts, placements * 4L + excavations * 8L,
                approach == Long.MAX_VALUE ? 0 : approach, Collections.unmodifiableMap(materials),
                Collections.unmodifiableMap(deficit), Collections.unmodifiableMap(histogram), List.copyOf(findings),
                unknown == 0 && assets == 0 && protectedCount == 0 && fluids == 0);
    }
}
