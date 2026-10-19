package com.mineagent.engine.construction;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mineagent.api.construction.ConstructionDesign;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** World/companion-scoped, append-only revisions. Call on the server thread. */
public final class ConstructionRepository {
    private static final Gson JSON = new Gson();
    private static final int MAX_DESIGNS = 128, MAX_REVISIONS = 128, MAX_EVENTS = 256;
    private final Path root;
    public record Revision(int schema, int version, ConstructionDesign design, String reason) {}
    public record Entry(String id, int version, String dimension, String purpose) {}
    public record Evidence(int sequence, int revision, String kind, long tick, JsonObject payload) {}
    public ConstructionRepository(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }
    /** Stable normalized namespace for bounded in-process review correlation, not a companion UUID. */
    public Path scope() { return root; }
    public synchronized Revision save(ConstructionDesign design, int expectedVersion, String reason) throws IOException {
        Objects.requireNonNull(design); ConstructionDesign.text(reason);
        Path directory = root.resolve(design.id());
        int latest = latest(design.id());
        if (latest != expectedVersion) throw new ConcurrentModificationException("revision_conflict");
        if (latest >= MAX_REVISIONS) throw new IllegalArgumentException("revision_limit");
        if (latest == 0 && directories().size() >= MAX_DESIGNS) throw new IllegalArgumentException("design_limit");
        Files.createDirectories(directory);
        var revision = new Revision(1, latest + 1, design, reason);
        publish(directory.resolve((latest + 1) + ".json"), JSON.toJson(revision));
        return revision;
    }
    /** Version zero selects latest. No fallback to older data after a corrupt read. */
    public synchronized Revision read(String id, int version) throws IOException {
        ConstructionDesign.requireId(id);
        if (version < 0 || version > MAX_REVISIONS) throw new IllegalArgumentException("revision");
        if (version == 0) version = latest(id);
        if (version == 0) throw new NoSuchFileException(id);
        try {
            var value = JSON.fromJson(readBounded(root.resolve(id).resolve(version + ".json"), 2_000_000), Revision.class);
            if (value == null || value.schema != 1 || value.version != version || value.design == null
                    || !value.design.id().equals(id)) throw new IllegalArgumentException("revision_schema");
            ConstructionDesign.text(value.reason);
            return value;
        } catch (RuntimeException malformed) { throw new IOException("invalid_revision", malformed); }
    }
    public synchronized List<Entry> list(String after, int limit) throws IOException {
        if (after == null || (!after.isEmpty() && !after.matches("[a-z0-9][a-z0-9_-]{0,47}"))
                || limit < 1 || limit > 20) throw new IllegalArgumentException("pagination");
        List<Entry> values = new ArrayList<>();
        for (String id : directories()) {
            if (id.compareTo(after) <= 0 || latest(id) == 0) continue;
            Revision r = read(id, 0);
            values.add(new Entry(id, r.version, r.design.dimension(), r.design.decisions().purpose()));
            if (values.size() == limit) break;
        }
        return List.copyOf(values);
    }
    public synchronized Evidence appendEvidence(String id, int revision, String kind, long tick, JsonObject payload) throws IOException {
        var r = read(id, revision);
        if (kind == null || !kind.matches("[a-z_]{1,32}") || tick < 0 || payload == null)
            throw new IllegalArgumentException("evidence");
        var existing = evidence(id, 0);
        Set<Integer> pending = new HashSet<>();
        for (var event : existing) {
            if (event.kind().equals("dispatch")) pending.add(event.sequence());
            else if (event.kind().equals("outcome") && event.payload().has("dispatch_sequence"))
                pending.remove(event.payload().get("dispatch_sequence").getAsInt());
        }
        if (kind.equals("outcome") && payload.has("dispatch_sequence")) pending.remove(payload.get("dispatch_sequence").getAsInt());
        // A physical dispatch owns one future terminal slot; previews/reviews cannot consume it.
        int reserved = pending.size() + (kind.equals("dispatch") ? 1 : 0);
        if (existing.size() + 1 + reserved > MAX_EVENTS) throw new IllegalArgumentException("evidence_limit");
        int sequence = existing.size() + 1;
        Evidence event = new Evidence(sequence, r.version, kind, tick, payload.deepCopy());
        String encoded = JSON.toJson(event);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 262144) throw new IllegalArgumentException("evidence_size");
        Path dir = root.resolve(id).resolve("evidence"); Files.createDirectories(dir);
        publish(dir.resolve(sequence + ".json"), encoded);
        return event;
    }
    /** Exact revision or all revisions when zero; bounded to MAX_EVENTS. */
    public synchronized List<Evidence> evidence(String id, int revision) throws IOException {
        ConstructionDesign.requireId(id);
        if (revision < 0 || revision > MAX_REVISIONS) throw new IllegalArgumentException("revision");
        List<Evidence> result = new ArrayList<>();
        Path dir = root.resolve(id).resolve("evidence");
        for (int n = 1; n <= MAX_EVENTS; n++) {
            Path file = dir.resolve(n + ".json"); if (!Files.exists(file)) break;
            try {
                Evidence e = JSON.fromJson(readBounded(file, 262144), Evidence.class);
                if (e == null || e.sequence != n || e.revision < 1 || e.payload == null || e.kind == null)
                    throw new IllegalArgumentException("evidence_schema");
                if (revision == 0 || e.revision == revision) result.add(e);
            } catch (RuntimeException malformed) { throw new IOException("invalid_evidence", malformed); }
        }
        return List.copyOf(result);
    }
    private int latest(String id) throws IOException {
        ConstructionDesign.requireId(id);
        int latest = 0;
        for (int n = 1; n <= MAX_REVISIONS; n++) {
            if (!Files.exists(root.resolve(id).resolve(n + ".json"))) break;
            latest = n;
        }
        return latest;
    }
    private List<String> directories() throws IOException {
        try (var entries = Files.list(root)) {
            return entries.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).filter(n -> n.matches("[a-z0-9][a-z0-9_-]{0,47}"))
                    .sorted().limit(MAX_DESIGNS + 1L).toList();
        }
    }
    private static String readBounded(Path path, long bytes) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > bytes) throw new IOException("file_limit");
        return Files.readString(path);
    }
    private static void publish(Path target, String json) throws IOException {
        // A completed temporary file is never interpreted as a revision after a crash.
        Path pending = Files.createTempFile(target.getParent(), ".pending-", ".json");
        try {
            Files.writeString(pending, json, StandardCharsets.UTF_8);
            Files.move(pending, target); // Never replace a published revision, including cross-instance races.
        } finally { Files.deleteIfExists(pending); }
    }
}
