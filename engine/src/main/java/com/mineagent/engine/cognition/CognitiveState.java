package com.mineagent.engine.cognition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ordered, versioned projection of the companion's durable cognition.
 *
 * <p>The existing specialised stores remain useful indexes, but this ledger
 * gives them one monotonic evidence order.  Events contain bounded semantic
 * metadata only; raw prompts, API keys, and live Minecraft objects never enter
 * it.  A restored in-progress body action is intentionally downgraded to an
 * observation requiring revalidation by the scheduler.</p>
 */
public final class CognitiveState {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_EVENTS = 4_096;
    private static final int MAX_PROJECTIONS = 512;

    public record Event(long sequence, String type, String key,
                        String value, long gameTick) {
        public Event {
            sequence = Math.max(1L, sequence);
            type = normalize(type, "event");
            key = normalize(key, "unknown");
            value = compact(value, 600);
            gameTick = Math.max(0L, gameTick);
        }
    }

    public record State(int schemaVersion, List<Event> events,
                        Map<String, String> projections,
                        long nextSequence, long revision) {
        public State {
            schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
            // Gson can deserialize a partially written checkpoint containing
            // null array/map members. Filter those members at the boundary so
            // one bad record does not discard the entire cognitive journal.
            events = events == null ? List.of() : events.stream()
                    .filter(java.util.Objects::nonNull).toList();
            projections = projections == null ? Map.of() : projections.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first));
            nextSequence = Math.max(1L, nextSequence);
            revision = Math.max(0L, revision);
        }
    }

    private final ArrayList<Event> events = new ArrayList<>();
    private final LinkedHashMap<String, String> projections = new LinkedHashMap<>();
    private long nextSequence = 1L;
    private long revision;

    public synchronized Event append(String type, String key, String value, long gameTick) {
        Event event = new Event(nextSequence++, type, key, value, gameTick);
        events.add(event);
        projections.put(event.key(), event.value());
        trim();
        revision++;
        return event;
    }

    public synchronized void setProjection(String key, String value, long gameTick) {
        append("projection", key, value, gameTick);
    }

    public synchronized String projection(String key) {
        return key == null ? null : projections.get(key.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized List<Event> eventsSince(long sequence) {
        return events.stream().filter(event -> event.sequence() > Math.max(0L, sequence)).toList();
    }

    public synchronized long revision() { return revision; }
    public synchronized long lastSequence() { return nextSequence - 1L; }

    public synchronized State exportState() {
        return new State(SCHEMA_VERSION, List.copyOf(events), Map.copyOf(projections),
                nextSequence, revision);
    }

    public synchronized void importState(State state) {
        events.clear();
        projections.clear();
        nextSequence = 1L;
        revision = 0L;
        if (state == null) return;
        long previous = 0L;
        if (state.events() != null) {
            for (Event event : state.events().stream().filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingLong(Event::sequence)).toList()) {
                if (event.sequence() <= previous) continue;
                events.add(event);
                previous = event.sequence();
                nextSequence = Math.max(nextSequence, event.sequence() + 1L);
            }
        }
        if (state.projections() != null) {
            state.projections().forEach((key, value) -> {
                if (key != null && value != null && projections.size() < MAX_PROJECTIONS) {
                    projections.put(key, compact(value, 600));
                }
            });
        }
        // Rebuild missing projections from the journal while preserving the
        // explicit checkpoint values when they are newer than an event.
        var explicitProjectionKeys = Set.copyOf(projections.keySet());
        for (Event event : events) {
            if (!explicitProjectionKeys.contains(event.key())) {
                // Events are ordered oldest-to-newest, so replacement rebuilds
                // the latest projection instead of freezing the first value.
                projections.put(event.key(), event.value());
            }
        }
        trim();
        revision = Math.max(0L, state.revision()) + 1L;
    }

    /** Compact diagnostic context for the LLM; newest semantic entries first. */
    public synchronized String summarizeForPrompt(int limit) {
        int max = Math.max(1, Math.min(24, limit));
        if (events.isEmpty()) return "Cognitive ledger: empty\n";
        StringBuilder out = new StringBuilder("Cognitive ledger (ordered evidence):\n");
        events.stream().sorted(Comparator.comparingLong(Event::sequence).reversed())
                .filter(event -> !"heartbeat".equals(event.type()))
                .limit(max)
                .forEach(event -> out.append("- #").append(event.sequence()).append(' ')
                        .append(event.type()).append(' ').append(event.key()).append('=')
                        .append(event.value()).append(" tick=").append(event.gameTick()).append('\n'));
        return out.toString();
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) events.remove(0);
        while (projections.size() > MAX_PROJECTIONS) {
            String oldest = projections.keySet().iterator().next();
            projections.remove(oldest);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }
    private static String compact(String value, int max) {
        if (value == null || value.isBlank()) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
