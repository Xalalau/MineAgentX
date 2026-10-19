package com.mineagent.api.construction;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable model-authored coordinates and decisions; deliberately no blueprint generator. */
public record ConstructionDesign(String id, String dimension, Point origin, int quarterTurns,
                                 Point size, Decisions decisions, List<Cell> cells) {
    public static final int MAX_CELLS = 4096;
    public static final int MAX_VOLUME = 32768;
    public ConstructionDesign {
        requireId(id);
        resourceId(dimension);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(size);
        Objects.requireNonNull(decisions);
        if (quarterTurns < 0 || quarterTurns > 3) throw new IllegalArgumentException("orientation");
        if (size.x < 1 || size.y < 1 || size.z < 1 || size.x > 64 || size.y > 64 || size.z > 64
                || (long) size.x * size.y * size.z > MAX_VOLUME) throw new IllegalArgumentException("size");
        if (cells == null || cells.isEmpty() || cells.size() > MAX_CELLS) throw new IllegalArgumentException("cells");
        cells = List.copyOf(cells);
        var seen = new HashSet<Point>();
        for (Cell cell : cells) {
            Point p = cell.position;
            if (p.x < 0 || p.y < 0 || p.z < 0 || p.x >= size.x || p.y >= size.y || p.z >= size.z
                    || !seen.add(p)) throw new IllegalArgumentException("duplicate_or_outside_cell");
        }
        // Bound the entire inspection envelope, including corners not occupied by cells.
        for (int x : new int[]{0, size.x - 1}) for (int z : new int[]{0, size.z - 1}) {
            transformed(origin, quarterTurns, new Point(x, 0, z));
            transformed(origin, quarterTurns, new Point(x, size.y - 1, z));
        }
    }
    public record Point(int x, int y, int z) {}
    public record Cell(Point position, String block) {
        public Cell { Objects.requireNonNull(position); resourceId(block); }
        public boolean clear() { return block.equals("minecraft:air"); }
    }
    public record Decisions(String purpose, String interiors, String access, String terrain,
                            String materials, String transport) {
        public Decisions {
            text(purpose); text(interiors); text(access); text(terrain); text(materials); text(transport);
        }
    }
    /** Zero turns: local +X east, +Z south, front -Z; positive turns rotate +X toward +Z. */
    public Point world(Point local) { return transformed(origin, quarterTurns, local); }
    /** Complete bill of block items, independent of site observations or previous execution. */
    public java.util.Map<String, Integer> materials() {
        var counts = new java.util.TreeMap<String, Integer>();
        for (Cell cell : cells) if (!cell.clear()) counts.merge(cell.block(), 1, Integer::sum);
        return java.util.Collections.unmodifiableMap(counts);
    }
    public ConstructionDesign at(String dimension, Point origin, int turns) {
        return new ConstructionDesign(id, dimension, origin, turns, size, decisions, cells);
    }
    private static Point transformed(Point origin, int turns, Point p) {
        long x = p.x, z = p.z;
        for (int i = 0; i < turns; i++) { long old = x; x = -z; z = old; }
        long wx = origin.x + x, wy = (long) origin.y + p.y, wz = origin.z + z;
        if (wx < -30000000 || wx > 30000000 || wz < -30000000 || wz > 30000000
                || wy < -2048 || wy > 2048) throw new IllegalArgumentException("world_bounds");
        return new Point((int) wx, (int) wy, (int) wz);
    }
    public static void requireId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,47}")) throw new IllegalArgumentException("design_id");
    }
    public static void resourceId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("resource_id");
    }
    public static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("decision_text");
    }
}
