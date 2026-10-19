package com.mineagent.engine.identity;

import java.util.List;

/** Evidence-backed personal memory, initially uncommitted. No predefined tastes. */
public final class EvolvingIdentity {
    public record Evidence(String id, String origin, String source, String text, long tick) {}
    private final java.util.LinkedHashMap<String, Evidence> observations = new java.util.LinkedHashMap<>();
    private long sequence;

    public synchronized Evidence recordEvidence(String origin, String source, String text, long tick) {
        if (origin == null || !java.util.Set.of("player_claim", "observation").contains(origin)) {
            throw new IllegalArgumentException("origin");
        }
        if (source == null || source.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("evidence");
        }
        source = source.strip();
        if (source.length() > 120) source = source.substring(0, 120);
        text = text.strip();
        if (text.length() > 360) text = text.substring(0, 360);
        if (!observations.isEmpty()) {
            Evidence last = observations.lastEntry().getValue();
            if (last.origin().equals(origin) && last.source().equals(source) && last.text().equals(text)) return last;
        }
        var item = new Evidence("e" + ++sequence, origin, source, text, Math.max(0, tick));
        observations.put(item.id(), item);
        var pinned = new java.util.HashSet<String>();
        for (Entry entry : learned.values()) {
            pinned.addAll(entry.evidenceIds());
            for (Revision revision : entry.history()) pinned.addAll(revision.evidenceIds());
        }
        var iterator = observations.keySet().iterator();
        while (observations.size() > 384 && iterator.hasNext()) {
            if (!pinned.contains(iterator.next())) iterator.remove();
        }
        return item;
    }
    public record Revision(String statement, String reason, List<String> evidenceIds,
                           double confidence, long revision, String reconsideration) {
        public Revision { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Entry(String kind, String subject, String statement, String reason,
                        List<String> evidenceIds, double confidence, long revision,
                        String reconsideration, List<Revision> history) {
        public Entry { evidenceIds = List.copyOf(evidenceIds); history = List.copyOf(history); }
    }
    private final java.util.LinkedHashMap<String, Entry> learned = new java.util.LinkedHashMap<>();

    public synchronized Entry revise(String kind, String subject, String statement, String reason,
                                     List<String> evidenceIds, double confidence,
                                     long expectedRevision, String reconsideration) {
        validateRevision(kind, subject, statement, reason, evidenceIds, confidence, reconsideration);
        String key = kind + ":" + subject;
        Entry previous = learned.get(key);
        if (expectedRevision != (previous == null ? 0 : previous.revision())) {
            throw new IllegalArgumentException("stale_revision");
        }
        if (previous != null && (reconsideration == null || reconsideration.isBlank()
                || evidenceIds.stream().allMatch(previous.evidenceIds()::contains))) {
            throw new IllegalArgumentException("revision_needs_new_evidence_and_reconsideration");
        }
        boolean observed = evidenceIds.stream().allMatch(id -> observations.containsKey(id)
                && "observation".equals(observations.get(id).origin()));
        var revisions = new java.util.ArrayList<Revision>();
        if (previous != null) {
            revisions.addAll(previous.history());
            revisions.add(new Revision(previous.statement(), previous.reason(), previous.evidenceIds(),
                    previous.confidence(), previous.revision(), previous.reconsideration()));
        }
        while (revisions.size() > 3) revisions.removeFirst();
        if (previous == null && learned.size() >= 24) throw new IllegalArgumentException("identity_capacity");
        var entry = new Entry(kind, subject, statement, reason, List.copyOf(evidenceIds),
                Math.min(confidence, observed ? 0.85 : 0.55), expectedRevision + 1,
                reconsideration, List.copyOf(revisions));
        learned.put(key, entry);
        return entry;
    }
    private void validateRevision(String kind, String subject, String statement, String reason,
            List<String> evidenceIds, double confidence, String reconsideration) {
        if (kind == null || !java.util.Set.of("preference", "goal", "world").contains(kind)
                || !validText(subject, 120) || !validText(statement, 300) || !validText(reason, 300)
                || reconsideration == null || reconsideration.length() > 300
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 3
                || evidenceIds.stream().anyMatch(id -> !observations.containsKey(id))) {
            throw new IllegalArgumentException("invalid_or_unsupported_revision");
        }
    }
    private static boolean validText(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max;
    }

    /** Metadata diversity with lexical ranking, not semantic or cross-language matching. */
    public synchronized String decisionContext(String query, String language, int maxChars) {
        StringBuilder out = new StringBuilder();
        var ranked = learned.values().stream()
                .filter(entry -> !entry.kind().equals("world") || query == null || query.isBlank() || relevance(query, entry) > 0)
                .sorted(java.util.Comparator.<Entry>comparingInt(entry -> relevance(query, entry)).reversed())
                .toList();
        var selected = new java.util.LinkedHashSet<Entry>();
        // Reserve one goal and one preference even without lexical overlap; never broaden world beliefs.
        // Ties retain store order, and all records still compete with the hard character budget.
        ranked.stream().filter(entry -> entry.kind().equals("goal")).findFirst().ifPresent(selected::add);
        ranked.stream().filter(entry -> entry.kind().equals("preference")).findFirst().ifPresent(selected::add);
        for (Entry entry : ranked) {
            if (selected.size() == 3) break;
            selected.add(entry);
        }
        for (Entry entry : selected) {
            boolean observed = entry.evidenceIds().stream().allMatch(id -> observations.containsKey(id)
                    && "observation".equals(observations.get(id).origin()));
            String provenance = IdentityText.text(language,
                    observed ? "observation-backed" : "player report, not verified",
                    observed ? "baseado em observação" : "relato do jogador, não verificado");
            String label = switch (entry.kind()) {
                case "goal" -> IdentityText.text(language, "Personal goal concerning ", "Objetivo pessoal sobre ");
                case "preference" -> IdentityText.text(language, "Learned preference concerning ", "Preferência aprendida sobre ");
                default -> IdentityText.text(language, "World belief concerning ", "Crença sobre o mundo referente a ");
            };
            String line = label + entry.subject() + ": " + entry.statement()
                    + IdentityText.text(language, ". Reason: ", ". Motivo: ") + entry.reason()
                    + " (" + provenance + "; " + entry.confidence() + "; r" + entry.revision() + ").\n";
            if (out.length() + line.length() <= Math.max(0, maxChars)) out.append(line);
        }
        return out.toString();
    }

    private static int relevance(String query, Entry entry) {
        if (query == null || query.isBlank()) return 0;
        String text = (entry.subject() + " " + entry.statement() + " " + entry.reason()).toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        for (String word : query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (word.length() > 2 && text.contains(word)) score++;
        }
        return score;
    }
    public synchronized List<Entry> entries() { return List.copyOf(learned.values()); }
    public record State(long sequence, List<Evidence> evidence, List<Entry> entries) {}
    public synchronized State exportState() { return new State(sequence, evidence(), entries()); }
    public synchronized void importState(State state) {
        if (state == null) {
            observations.clear(); learned.clear(); sequence = 0;
            return;
        }
        if (state.sequence() < 0 || state.sequence() == Long.MAX_VALUE
                || state.evidence() == null || state.evidence().size() > 384
                || state.entries() == null || state.entries().size() > 24) {
            throw new IllegalArgumentException("invalid_identity_state");
        }
        var candidate = new EvolvingIdentity();
        for (Evidence item : state.evidence()) {
            if (item == null || item.id() == null || !item.id().matches("e[1-9][0-9]*")
                    || Long.parseLong(item.id().substring(1)) > state.sequence()
                    || !java.util.Set.of("player_claim", "observation").contains(item.origin())
                    || !validText(item.source(), 120) || !validText(item.text(), 360) || item.tick() < 0
                    || candidate.observations.putIfAbsent(item.id(), item) != null) {
                throw new IllegalArgumentException("invalid_identity_evidence");
            }
        }
        for (Entry entry : state.entries()) {
            if (entry == null || entry.revision() < 1 || entry.history() == null || entry.history().size() > 3) {
                throw new IllegalArgumentException("invalid_identity_entry");
            }
            candidate.validateRevision(entry.kind(), entry.subject(), entry.statement(), entry.reason(),
                    entry.evidenceIds(), entry.confidence(), entry.reconsideration());
            long previous = entry.revision() - entry.history().size() - 1;
            for (Revision old : entry.history()) {
                if (old == null || old.revision() != ++previous) throw new IllegalArgumentException("invalid_identity_history");
                candidate.validateRevision(entry.kind(), entry.subject(), old.statement(), old.reason(),
                        old.evidenceIds(), old.confidence(), old.reconsideration());
            }
            if (candidate.learned.putIfAbsent(entry.kind() + ":" + entry.subject(), entry) != null) {
                throw new IllegalArgumentException("duplicate_identity_entry");
            }
        }
        observations.clear(); observations.putAll(candidate.observations);
        learned.clear(); learned.putAll(candidate.learned);
        sequence = state.sequence();
    }
    public synchronized long evidenceSequence() { return sequence; }
    public synchronized List<Evidence> evidence() { return List.copyOf(observations.values()); }
}
